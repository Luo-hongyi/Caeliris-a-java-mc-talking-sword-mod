package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AttentionFilter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagRelationDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * 长期记忆召回器：统一模型下的一轮 recall 执行器。
 * <p>
 * 当前版本的标准顺序为：
 * <ol>
 * <li>`visibleContexts -> tag_pool`</li>
 * <li>`tag_pool -> 主召回候选`</li>
 * <li>`旧 fermentation + 新候选 -> retained fermentation`</li>
 * <li>`retained fermentation -> conscious memories`</li>
 * <li>`conscious memories -> prompt fill`</li>
 * <li>`conscious memories -> association sandbox (1-hop)`</li>
 * </ol>
 * <p>
 * 其中 sandbox 联想只服务本轮输出，不回写主状态。
 */
public final class LTMRecallEngine {

    private static final double DEFAULT_DESIRE_PROFILE_BONUS_SCALE = 0.25;
    private static final double DEFAULT_DESIRED_FOCUS_BONUS_SCALE = 0.18;
    private static final double RECENT_RECALL_PENALTY_FLOOR = 0.02;
    private static final double RECENT_RECALL_PENALTY_CEIL = 0.86;
    private static final double RECENT_RECALL_RELEASE_START = 0.15;
    private static final double RECENT_RECALL_RELEASE_END = 0.85;
    private static final double RECENT_RECALL_CURVE_EXPONENT = 1.8;
    private static final int INITIAL_SEED_TRACE_PREVIEW_LIMIT = 12;
    private static final double FERMENTATION_RETENTION_SCORE_THRESHOLD = 0.16;
    private static final int RETENTION_BUFFER_MIN = 8;
    private static final int RETENTION_BUFFER_MAX = 32;

    private LTMRecallEngine() {
    }

    public static final class RecallResult {
        public final List<LTMEntry> consciousMemories;
        public final List<LTMEntry> promptMemories;
        public final List<LTMEntry> recalledMemories;
        public final Set<String> triggerTags;
        public final List<String> traceLines;
        public final RecallRuntimeState.Snapshot runtimeState;

        public RecallResult(List<LTMEntry> promptMemories, Set<String> triggerTags, List<String> traceLines) {
            this(promptMemories, promptMemories, triggerTags, traceLines, null);
        }

        public RecallResult(List<LTMEntry> promptMemories, Set<String> triggerTags, List<String> traceLines,
                RecallRuntimeState.Snapshot runtimeState) {
            this(promptMemories, promptMemories, triggerTags, traceLines, runtimeState);
        }

        public RecallResult(List<LTMEntry> consciousMemories, List<LTMEntry> promptMemories,
                Set<String> triggerTags, List<String> traceLines,
                RecallRuntimeState.Snapshot runtimeState) {
            this.consciousMemories = Collections.unmodifiableList(new ArrayList<>(consciousMemories));
            this.promptMemories = Collections.unmodifiableList(new ArrayList<>(promptMemories));
            this.recalledMemories = this.promptMemories;
            this.triggerTags = Collections.unmodifiableSet(new LinkedHashSet<>(triggerTags));
            this.traceLines = Collections.unmodifiableList(new ArrayList<>(traceLines));
            this.runtimeState = runtimeState != null ? runtimeState : new RecallRuntimeState.Snapshot();
        }

        public boolean isEmpty() {
            return promptMemories.isEmpty();
        }
    }

    private static final class RecallPresetProfile {
        final int mainCandidatesPerCue;
        final int sandboxCuesPerMemory;
        final int consciousMemoryCap;

        private RecallPresetProfile(int mainCandidatesPerCue, int sandboxCuesPerMemory, int consciousMemoryCap) {
            this.mainCandidatesPerCue = mainCandidatesPerCue;
            this.sandboxCuesPerMemory = sandboxCuesPerMemory;
            this.consciousMemoryCap = consciousMemoryCap;
        }

        static RecallPresetProfile fromState(AgentState state) {
            AgentState.RecallPreset preset = state != null ? state.getRecallPreset() : null;
            if (preset == null) {
                preset = AgentState.RecallPreset.HYBRID;
            }
            switch (preset) {
                case SEED_FIRST:
                    return new RecallPresetProfile(1, 1, 2);
                case FULL_VISIBILITY:
                    return new RecallPresetProfile(2, 1, 4);
                case HYBRID:
                    return new RecallPresetProfile(1, 2, 3);
                case CUSTOM: {
                    AgentState.RecallTuning recallTuning = state != null
                            ? state.getRecallTuning()
                            : AgentState.RecallTuning.defaults();
                    return new RecallPresetProfile(
                            recallTuning.getMainCandidatesPerCue(),
                            recallTuning.getSandboxCuesPerMemory(),
                            recallTuning.getConsciousMemoryCap());
                }
                default:
                    return new RecallPresetProfile(1, 2, 3);
            }
        }
    }

    private static final class FocusProfile {
        final EnumMap<FocusTag, Double> normalizedWeights;
        final FocusTag dominantFocus;

        FocusProfile(EnumMap<FocusTag, Double> normalizedWeights, FocusTag dominantFocus) {
            this.normalizedWeights = normalizedWeights;
            this.dominantFocus = dominantFocus;
        }

        double alignment(Collection<FocusTag> focusTags) {
            if (focusTags == null || focusTags.isEmpty()) {
                return 0.0;
            }
            double total = 0.0;
            for (FocusTag tag : focusTags) {
                total += normalizedWeights.getOrDefault(tag, 0.0) * focusRecallWeight(tag);
            }
            return clamp01(total / Math.max(1.0, focusTags.size()));
        }
    }

    private static final class Seed {
        final DetailTag tag;
        final Set<FocusTag> focusTags;
        final double sourceScore;

        Seed(DetailTag tag, Collection<FocusTag> focusTags, double sourceScore) {
            this.tag = tag;
            this.focusTags = Collections.unmodifiableSet(new LinkedHashSet<>(focusTags));
            this.sourceScore = sourceScore;
        }
    }

    private static final class TagSeedCandidate {
        final DetailTag tag;
        final double score;

        TagSeedCandidate(DetailTag tag, double score) {
            this.tag = tag;
            this.score = score;
        }
    }

    private static final class MemoryCandidate {
        final LTMEntry entry;
        double combinedScore;
        double bestSingleScore;
        double bestSemanticScore;
        double bestFocusAlignment;
        double bestAgeFactor;
        DetailTag bestMatchedTag;
        final Set<String> supportTags = new LinkedHashSet<>();
        int supportCount;

        MemoryCandidate(LTMEntry entry) {
            this.entry = entry;
        }

        void addEvidence(Seed seed, double score, double semanticScore, double focusAlignment,
                double ageFactor, DetailTag matchedTag) {
            supportCount++;
            supportTags.add(seed.tag.toString());
            combinedScore += supportCount == 1 ? score : score * 0.55;
            if (score > bestSingleScore) {
                bestSingleScore = score;
                bestSemanticScore = semanticScore;
                bestFocusAlignment = focusAlignment;
                bestAgeFactor = ageFactor;
                bestMatchedTag = matchedTag;
            }
        }

        void addFermentationEvidence(String memoryId, double score, double fermentationScore, double focusAlignment,
                Collection<String> supportTags) {
            supportCount++;
            if (supportTags != null && !supportTags.isEmpty()) {
                this.supportTags.addAll(supportTags);
            }
            combinedScore += supportCount == 1 ? score : score * 0.55;
            if (score > bestSingleScore) {
                bestSingleScore = score;
                bestSemanticScore = fermentationScore;
                bestFocusAlignment = focusAlignment;
                bestAgeFactor = 1.0;
                bestMatchedTag = null;
            }
        }
    }

    private static final class ImplantedFallbackCandidate {
        final LTMEntry entry;
        double rawWeight;
        double weight;
        int hitCount;
        double recentPenalty = 1.0;
        final Set<String> matchedSeedTags = new LinkedHashSet<>();
        final Set<String> matchedMemoryTags = new LinkedHashSet<>();

        ImplantedFallbackCandidate(LTMEntry entry) {
            this.entry = entry;
        }
    }

    private static final class SelectionCandidate {
        final LTMEntry entry;
        double score;
        double consciousScore;
        double consciousPenalty = 1.0;
        double cueBoostThisTurn;
        long lastUpdatedGameTime = -1L;
        long lastConsciousGameTime = -1L;
        final Set<String> focusTags = new LinkedHashSet<>();
        final Set<String> supportTags = new LinkedHashSet<>();
        final Set<String> associativeTags = new LinkedHashSet<>();

        SelectionCandidate(LTMEntry entry) {
            this.entry = entry;
            if (entry != null) {
                for (FocusTag focusTag : entry.getFocusTags()) {
                    if (focusTag != null) {
                        focusTags.add(focusTag.name());
                    }
                }
                for (DetailTag detailTag : entry.getAssociativeDetailTags()) {
                    if (detailTag != null) {
                        associativeTags.add(detailTag.toString());
                    }
                }
            }
        }

        void absorbFermentation(RecallRuntimeState.FermentationMemoryEntry fermentationEntry) {
            if (fermentationEntry == null) {
                return;
            }
            score = clamp(0.0, 3.0, Math.max(score, fermentationEntry.score));
            cueBoostThisTurn = Math.max(cueBoostThisTurn, fermentationEntry.cueBoostThisTurn);
            lastUpdatedGameTime = Math.max(lastUpdatedGameTime, fermentationEntry.lastUpdatedGameTime);
            lastConsciousGameTime = Math.max(lastConsciousGameTime, fermentationEntry.lastConsciousGameTime);
            focusTags.addAll(fermentationEntry.focusTags);
            supportTags.addAll(fermentationEntry.supportTags);
            associativeTags.addAll(fermentationEntry.associativeTags);
        }

        void absorbPromotedCandidate(MemoryCandidate candidate, long currentGameTime) {
            if (candidate == null) {
                return;
            }
            double incomingScore = clamp(0.0, 3.0, candidate.combinedScore);
            score = clamp(0.0, 3.0, Math.max(score, score > 0.0 ? score * 0.55 + incomingScore : incomingScore));
            lastUpdatedGameTime = currentGameTime;
            supportTags.addAll(candidate.supportTags);
            if (candidate.bestMatchedTag != null) {
                supportTags.add(candidate.bestMatchedTag.toString());
            }
        }

        RecallRuntimeState.FermentationMemoryEntry toFermentationEntry(long currentGameTime, boolean enteredConscious) {
            RecallRuntimeState.FermentationMemoryEntry entry = new RecallRuntimeState.FermentationMemoryEntry();
            entry.memoryId = this.entry.getId().toString();
            entry.description = this.entry.getDescription() != null ? this.entry.getDescription() : "";
            entry.score = clamp(0.0, 3.0, this.score);
            entry.sourceType = this.entry.getSourceType().name();
            entry.focusTags.addAll(this.focusTags);
            entry.supportTags.addAll(this.supportTags);
            entry.associativeTags.addAll(this.associativeTags);
            entry.lastUpdatedGameTime = currentGameTime;
            entry.lastConsciousGameTime = enteredConscious
                    ? currentGameTime
                    : Math.max(this.lastConsciousGameTime, -1L);
            entry.enteredConsciousThisTurn = enteredConscious;
            entry.cueBoostThisTurn = this.cueBoostThisTurn;
            return entry;
        }

        RecallRuntimeState.ConsciousMemoryEntry toConsciousEntry() {
            RecallRuntimeState.ConsciousMemoryEntry entry = new RecallRuntimeState.ConsciousMemoryEntry();
            entry.memoryId = this.entry.getId().toString();
            entry.description = this.entry.getDescription() != null ? this.entry.getDescription() : "";
            entry.score = clamp(0.0, 3.0, this.consciousScore);
            entry.sourceType = this.entry.getSourceType().name();
            entry.focusTags.addAll(this.focusTags);
            entry.supportTags.addAll(this.supportTags);
            entry.associativeTags.addAll(this.associativeTags);
            return entry;
        }
    }

    public static RecallResult recall(List<TaggedContextEntry> visibleContexts, AgentMemory memory, AgentState state,
            long currentGameTime) {
        RecallRuntimeState runtimeState = memory != null ? memory.getRecallRuntimeState() : null;
        if (runtimeState != null) {
            runtimeState.beginTurn(visibleContexts, state, currentGameTime);
        }

        if (memory == null || memory.getLtmRecords().isEmpty()) {
            if (runtimeState != null) {
                runtimeState.applySelectionOutcome(Collections.emptyList(), Collections.emptyList());
            }
            return new RecallResult(
                    Collections.emptyList(),
                    Collections.emptySet(),
                    Collections.emptyList(),
                    runtimeState != null ? runtimeState.snapshot() : null);
        }

        RecallPresetProfile profile = RecallPresetProfile.fromState(state);
        if (profile.consciousMemoryCap <= 0) {
            if (runtimeState != null) {
                runtimeState.applySelectionOutcome(Collections.emptyList(), Collections.emptyList());
            }
            return new RecallResult(
                    Collections.emptyList(),
                    Collections.emptySet(),
                    Collections.emptyList(),
                    runtimeState != null ? runtimeState.snapshot() : null);
        }

        memory.setRecentRecallWindow(TalkingWeaponConfig.getEffectiveRecentRecallWindow());
        memory.beginRecentRecallTurn();

        float concentration = state != null ? state.getConcentration() : 0.5f;
        float wanderingMind = state != null ? state.getWanderingMind() : 0.1f;
        FocusTag desireTag = state != null ? state.getCurrentDesireTag() : null;
        long now = System.currentTimeMillis();

        List<TaggedContextEntry> safeVisibleContexts = visibleContexts != null ? visibleContexts : Collections.emptyList();
        FocusProfile focusProfile = buildFocusProfile(safeVisibleContexts, concentration, desireTag);
        List<RecallRuntimeState.TagPoolEntry> initialTagPool = runtimeState != null
                ? runtimeState.snapshotTagPool()
                : Collections.emptyList();
        List<RecallRuntimeState.FermentationMemoryEntry> fermentationSnapshot = runtimeState != null
                ? runtimeState.snapshotFermentationBuffer()
                : Collections.emptyList();
        List<String> traceLines = new ArrayList<>();
        if (focusProfile.dominantFocus != null) {
            traceLines.add("[注意力方向] dominant_focus=" + focusProfile.dominantFocus.name());
        }

        List<Seed> activeCueInputs = buildInitialCueInputs(initialTagPool, safeVisibleContexts.size(), traceLines);
        Set<String> triggerTags = new LinkedHashSet<>();
        Set<UUID> promotedIds = new LinkedHashSet<>();
        List<MemoryCandidate> promotedCandidates = new ArrayList<>();
        List<Seed> allCueInputsThisTurn = new ArrayList<>();
        TagRelationDictionary relationDict = TagRelationDictionary.getInstance();

        int retentionCap = computeRetentionCapacity(profile);
        if (activeCueInputs.isEmpty()) {
            traceLines.add(fermentationSnapshot.isEmpty()
                    ? "[记忆起点] no active tag-pool cues and fermentation is empty"
                    : "[记忆起点] no active tag-pool cues, fallback to fermentation-only competition");
            if (!fermentationSnapshot.isEmpty()) {
                List<MemoryCandidate> fermentationOnlyCandidates = collectLayerCandidates(
                        Collections.emptyList(),
                        fermentationSnapshot,
                        memory,
                        promotedIds,
                        relationDict,
                        focusProfile,
                        state,
                        currentGameTime);
                promotedCandidates.addAll(selectPromotionCandidates(
                        fermentationOnlyCandidates,
                        Math.min(retentionCap, fermentationOnlyCandidates.size()),
                        wanderingMind));
            }
        } else {
            List<Seed> mainCueBatch = new ArrayList<>();
            for (Seed cueInput : activeCueInputs) {
                mainCueBatch.add(cueInput);
                triggerTags.add(cueInput.tag.toString());
            }
            allCueInputsThisTurn.addAll(mainCueBatch);

            traceLines.add(String.format("[主召回] cue_count=%d fermentation=%d", mainCueBatch.size(),
                    fermentationSnapshot.size()));
            List<MemoryCandidate> candidates = collectLayerCandidates(
                    mainCueBatch,
                    fermentationSnapshot,
                    memory,
                    promotedIds,
                    relationDict,
                    focusProfile,
                    state,
                    currentGameTime);
            if (candidates.isEmpty()) {
                traceLines.add("[主召回] no candidate memories");
            } else {
                int layerBudget = Math.min(retentionCap,
                        Math.max(1, Math.min(candidates.size(), mainCueBatch.size() * profile.mainCandidatesPerCue)));
                List<MemoryCandidate> picked = selectPromotionCandidates(candidates, layerBudget, wanderingMind);
                for (MemoryCandidate candidate : picked) {
                    if (!promotedIds.add(candidate.entry.getId())) {
                        continue;
                    }
                    promotedCandidates.add(candidate);
                    if (candidate.bestMatchedTag != null) {
                        triggerTags.add(candidate.bestMatchedTag.toString());
                    }

                    traceLines.add(String.format(
                            "[候选提升] score=%.2f supports=%d matched=%s semantic=%.2f focus=%.2f ageFactor=%.2f memory=%s",
                            candidate.combinedScore,
                            candidate.supportCount,
                            candidate.bestMatchedTag != null ? candidate.bestMatchedTag : "(focus-only)",
                            candidate.bestSemanticScore,
                            candidate.bestFocusAlignment,
                            candidate.bestAgeFactor,
                            candidate.entry.getDescription()));
                    if (!candidate.supportTags.isEmpty()) {
                        traceLines.add("    support_tags=" + candidate.supportTags);
                    }
                }
            }
        }

        List<SelectionCandidate> retainedCandidates = buildRetainedCandidates(
                promotedCandidates,
                fermentationSnapshot,
                memory,
                currentGameTime);
        List<SelectionCandidate> retainedSelection = selectTopCandidates(retainedCandidates, retentionCap);
        List<SelectionCandidate> consciousSelection = selectConsciousCandidates(
                retainedSelection,
                memory,
                profile.consciousMemoryCap);
        Set<UUID> forcedPromptIds = new LinkedHashSet<>();

        if (state != null
                && state.isImplantedMemorySafetyNetEnabled()
                && !containsImplantedSelection(consciousSelection)
                && !allCueInputsThisTurn.isEmpty()) {
            Set<UUID> consciousIds = toMemoryIdSet(consciousSelection);
            LTMEntry implantedFallback = pickWeightedImplantedFallback(
                    allCueInputsThisTurn, memory, consciousIds, relationDict, wanderingMind, traceLines);
            if (implantedFallback != null && consciousIds.add(implantedFallback.getId())) {
                SelectionCandidate fallbackSelection = new SelectionCandidate(implantedFallback);
                fallbackSelection.score = clamp(0.0, 3.0, TalkingWeaponConfig.getImplantedFallbackEntryScoreValue());
                fallbackSelection.consciousScore = fallbackSelection.score;
                fallbackSelection.consciousPenalty = 1.0;
                fallbackSelection.lastUpdatedGameTime = currentGameTime;
                traceLines.add(String.format("[植入记忆保底注入] entry_score=%.2f memory=%s",
                        fallbackSelection.score,
                        implantedFallback.getDescription()));
                consciousSelection = mergeFallbackIntoConscious(
                        consciousSelection,
                        fallbackSelection,
                        profile.consciousMemoryCap);
                retainedSelection = mergeFallbackIntoRetained(retainedSelection, fallbackSelection, retentionCap);
                forcedPromptIds.add(implantedFallback.getId());
            }
        }

        List<SelectionCandidate> promptSelection = fillPromptMemories(consciousSelection, forcedPromptIds, traceLines);
        List<LTMEntry> consciousMemories = materializeSelection(consciousSelection, now, false);
        List<LTMEntry> promptMemories = materializeSelection(promptSelection, now, true);
        Set<UUID> consciousIds = toMemoryIdSet(consciousSelection);
        Set<UUID> promptIds = toMemoryIdSet(promptSelection);

        List<SelectionCandidate> associationSelection = recallAssociationSandbox(
                consciousSelection,
                memory,
                relationDict,
                focusProfile,
                state,
                currentGameTime,
                wanderingMind,
                profile,
                traceLines);

        traceLines.add(String.format("[三层筛选] promoted=%d retained=%d conscious=%d prompt=%d",
                promotedCandidates.size(), retainedSelection.size(), consciousSelection.size(), promptSelection.size()));
        for (SelectionCandidate candidate : consciousSelection) {
            traceLines.add(String.format("[表层意识] consciousScore=%.2f retainedScore=%.2f recentPenalty=%.2f supports=%d memory=%s",
                    candidate.consciousScore,
                    candidate.score,
                    candidate.consciousPenalty,
                    candidate.supportTags.size(),
                    candidate.entry.getDescription()));
            if (!candidate.supportTags.isEmpty()) {
                traceLines.add("    conscious_support_tags=" + candidate.supportTags);
            }
        }

        memory.noteRecalledMemories(promptMemories);
        if (runtimeState != null) {
            runtimeState.applySelectionOutcome(
                    toFermentationEntries(retainedSelection, currentGameTime, consciousIds, promptIds),
                    toConsciousEntries(consciousSelection),
                    promptMemories.size());
            runtimeState.applyAssociationSandboxOutcome(toMemoryIds(associationSelection));
        }
        return new RecallResult(
                consciousMemories,
                promptMemories,
                triggerTags,
                traceLines,
                runtimeState != null ? runtimeState.snapshot() : null);
    }

    private static int computeRetentionCapacity(RecallPresetProfile profile) {
        int requested = Math.max(profile.consciousMemoryCap * 4, RETENTION_BUFFER_MIN);
        return Math.max(RETENTION_BUFFER_MIN, Math.min(RETENTION_BUFFER_MAX, requested));
    }

    private static List<MemoryCandidate> selectPromotionCandidates(List<MemoryCandidate> candidates, int limit,
            float wanderingMind) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        return sampleWeightedWithoutReplacement(candidates, candidate -> candidate.combinedScore, limit, wanderingMind);
    }

    private static List<SelectionCandidate> buildRetainedCandidates(List<MemoryCandidate> promotedCandidates,
            List<RecallRuntimeState.FermentationMemoryEntry> fermentationSnapshot,
            AgentMemory memory, long currentGameTime) {
        Map<UUID, LTMEntry> memoryById = new LinkedHashMap<>();
        for (LTMEntry entry : memory.getLtmRecords()) {
            if (entry != null && entry.getId() != null) {
                memoryById.put(entry.getId(), entry);
            }
        }

        Map<UUID, SelectionCandidate> selectionMap = new LinkedHashMap<>();
        if (fermentationSnapshot != null) {
            for (RecallRuntimeState.FermentationMemoryEntry fermentationEntry : fermentationSnapshot) {
                if (fermentationEntry == null || fermentationEntry.memoryId == null || fermentationEntry.memoryId.isBlank()) {
                    continue;
                }
                UUID memoryId;
                try {
                    memoryId = UUID.fromString(fermentationEntry.memoryId);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                LTMEntry memoryEntry = memoryById.get(memoryId);
                if (memoryEntry == null) {
                    continue;
                }
                SelectionCandidate selectionCandidate = selectionMap.computeIfAbsent(
                        memoryId, ignored -> new SelectionCandidate(memoryEntry));
                selectionCandidate.absorbFermentation(fermentationEntry);
            }
        }

        if (promotedCandidates != null) {
            for (MemoryCandidate promotedCandidate : promotedCandidates) {
                if (promotedCandidate == null
                        || promotedCandidate.entry == null
                        || promotedCandidate.entry.getId() == null) {
                    continue;
                }
                SelectionCandidate selectionCandidate = selectionMap.computeIfAbsent(
                        promotedCandidate.entry.getId(),
                        ignored -> new SelectionCandidate(promotedCandidate.entry));
                selectionCandidate.absorbPromotedCandidate(promotedCandidate, currentGameTime);
            }
        }

        return new ArrayList<>(selectionMap.values());
    }

    private static List<SelectionCandidate> selectTopCandidates(List<SelectionCandidate> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<SelectionCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort((left, right) -> {
            int byScore = Double.compare(right.score, left.score);
            if (byScore != 0) {
                return byScore;
            }
            int bySupport = Integer.compare(right.supportTags.size(), left.supportTags.size());
            if (bySupport != 0) {
                return bySupport;
            }
            return left.entry.getId().toString().compareTo(right.entry.getId().toString());
        });
        if (ordered.size() <= limit) {
            return ordered;
        }
        return new ArrayList<>(ordered.subList(0, limit));
    }

    private static List<SelectionCandidate> selectConsciousCandidates(List<SelectionCandidate> retainedSelection,
            AgentMemory memory, int limit) {
        if (retainedSelection == null || retainedSelection.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<SelectionCandidate> ranked = new ArrayList<>(retainedSelection);
        for (SelectionCandidate candidate : ranked) {
            if (candidate == null || candidate.entry == null || candidate.entry.getId() == null) {
                continue;
            }
            candidate.consciousPenalty = computeRecentRecallPenalty(memory, candidate.entry.getId());
            candidate.consciousScore = clamp(0.0, 3.0, candidate.score * candidate.consciousPenalty);
        }
        return selectTopConsciousCandidates(ranked, limit);
    }

    private static List<SelectionCandidate> selectTopConsciousCandidates(List<SelectionCandidate> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<SelectionCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort((left, right) -> {
            int byConscious = Double.compare(right.consciousScore, left.consciousScore);
            if (byConscious != 0) {
                return byConscious;
            }
            int byRetained = Double.compare(right.score, left.score);
            if (byRetained != 0) {
                return byRetained;
            }
            int bySupport = Integer.compare(right.supportTags.size(), left.supportTags.size());
            if (bySupport != 0) {
                return bySupport;
            }
            return left.entry.getId().toString().compareTo(right.entry.getId().toString());
        });
        if (ordered.size() <= limit) {
            return ordered;
        }
        return new ArrayList<>(ordered.subList(0, limit));
    }

    private static List<SelectionCandidate> fillPromptMemories(List<SelectionCandidate> consciousSelection,
            Set<UUID> forcedPromptIds, List<String> traceLines) {
        if (consciousSelection == null || consciousSelection.isEmpty()) {
            return Collections.emptyList();
        }
        List<SelectionCandidate> promptSelection = new ArrayList<>();
        for (SelectionCandidate candidate : consciousSelection) {
            if (candidate == null || candidate.entry == null || candidate.entry.getId() == null) {
                continue;
            }

            boolean forced = forcedPromptIds != null && forcedPromptIds.contains(candidate.entry.getId());
            double probability = forced ? 1.0 : computePromptFillProbability(candidate.consciousScore);
            double roll = forced ? 0.0 : ThreadLocalRandom.current().nextDouble();
            boolean accepted = forced || roll < probability;

            traceLines.add(String.format(
                    "[Prompt填充阀门] consciousScore=%.2f speakProbability=%.2f roll=%.2f accepted=%s memory=%s%s",
                    candidate.consciousScore,
                    probability,
                    roll,
                    accepted ? "true" : "false",
                    candidate.entry.getDescription(),
                    forced ? " [forced]" : ""));

            if (accepted) {
                promptSelection.add(candidate);
            }
        }
        traceLines.add(String.format("[Prompt填充] conscious=%d filled=%d",
                consciousSelection.size(), promptSelection.size()));
        return promptSelection;
    }

    private static double computePromptFillProbability(double consciousScore) {
        double normalized = clamp01(consciousScore / 2.40);
        double rawProbability = 0.08 + normalized * 0.84;
        double scale = TalkingWeaponConfig.getPromptFillProbabilityScaleValue();
        return clamp(0.0, 1.0, rawProbability * scale);
    }

    private static List<LTMEntry> materializeSelection(List<SelectionCandidate> selection, long now, boolean markAccessed) {
        if (selection == null || selection.isEmpty()) {
            return Collections.emptyList();
        }
        List<LTMEntry> recalled = new ArrayList<>(selection.size());
        for (SelectionCandidate candidate : selection) {
            if (candidate == null || candidate.entry == null) {
                continue;
            }
            if (markAccessed) {
                candidate.entry.markAccessed(now);
            }
            recalled.add(candidate.entry);
        }
        return recalled;
    }

    private static List<String> toMemoryIds(List<SelectionCandidate> selection) {
        if (selection == null || selection.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> memoryIds = new ArrayList<>(selection.size());
        for (SelectionCandidate candidate : selection) {
            if (candidate != null && candidate.entry != null && candidate.entry.getId() != null) {
                memoryIds.add(candidate.entry.getId().toString());
            }
        }
        return memoryIds;
    }

    private static List<RecallRuntimeState.ConsciousMemoryEntry> toConsciousEntries(
            List<SelectionCandidate> consciousSelection) {
        if (consciousSelection == null || consciousSelection.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecallRuntimeState.ConsciousMemoryEntry> entries = new ArrayList<>(consciousSelection.size());
        for (SelectionCandidate candidate : consciousSelection) {
            if (candidate == null || candidate.entry == null || candidate.entry.getId() == null) {
                continue;
            }
            entries.add(candidate.toConsciousEntry());
        }
        return entries;
    }

    private static List<RecallRuntimeState.FermentationMemoryEntry> toFermentationEntries(
            List<SelectionCandidate> retainedSelection, long currentGameTime,
            Set<UUID> consciousIds, Set<UUID> excludedIds) {
        if (retainedSelection == null || retainedSelection.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecallRuntimeState.FermentationMemoryEntry> entries = new ArrayList<>(retainedSelection.size());
        for (SelectionCandidate candidate : retainedSelection) {
            if (candidate == null || candidate.entry == null || candidate.entry.getId() == null) {
                continue;
            }
            if (excludedIds != null && excludedIds.contains(candidate.entry.getId())) {
                continue;
            }
            entries.add(candidate.toFermentationEntry(
                    currentGameTime,
                    consciousIds != null && consciousIds.contains(candidate.entry.getId())));
        }
        return entries;
    }

    private static List<SelectionCandidate> mergeFallbackIntoRetained(List<SelectionCandidate> retainedSelection,
            SelectionCandidate fallbackSelection, int retentionCap) {
        List<SelectionCandidate> merged = new ArrayList<>();
        if (retainedSelection != null) {
            merged.addAll(retainedSelection);
        }
        boolean exists = false;
        for (SelectionCandidate candidate : merged) {
            if (candidate.entry.getId().equals(fallbackSelection.entry.getId())) {
                candidate.score = Math.max(candidate.score, fallbackSelection.score);
                exists = true;
                break;
            }
        }
        if (!exists) {
            merged.add(fallbackSelection);
        }
        List<SelectionCandidate> ordered = selectTopCandidates(merged, merged.size());
        if (ordered.size() <= retentionCap) {
            return ordered;
        }
        if (containsCandidate(ordered.subList(0, retentionCap), fallbackSelection.entry.getId())) {
            return new ArrayList<>(ordered.subList(0, retentionCap));
        }
        List<SelectionCandidate> kept = new ArrayList<>();
        kept.add(findCandidate(ordered, fallbackSelection.entry.getId(), fallbackSelection));
        for (SelectionCandidate candidate : ordered) {
            if (kept.size() >= retentionCap) {
                break;
            }
            if (candidate.entry.getId().equals(fallbackSelection.entry.getId())) {
                continue;
            }
            kept.add(candidate);
        }
        return selectTopCandidates(kept, retentionCap);
    }

    private static List<SelectionCandidate> mergeFallbackIntoConscious(List<SelectionCandidate> consciousSelection,
            SelectionCandidate fallbackSelection, int consciousCap) {
        List<SelectionCandidate> merged = new ArrayList<>();
        if (consciousSelection != null) {
            merged.addAll(consciousSelection);
        }
        boolean exists = false;
        for (SelectionCandidate candidate : merged) {
            if (candidate.entry.getId().equals(fallbackSelection.entry.getId())) {
                candidate.score = Math.max(candidate.score, fallbackSelection.score);
                candidate.consciousPenalty = Math.max(candidate.consciousPenalty, fallbackSelection.consciousPenalty);
                candidate.consciousScore = Math.max(candidate.consciousScore, fallbackSelection.consciousScore);
                exists = true;
                break;
            }
        }
        if (!exists) {
            merged.add(fallbackSelection);
        }
        List<SelectionCandidate> ordered = selectTopConsciousCandidates(merged, merged.size());
        if (ordered.size() <= consciousCap) {
            return ordered;
        }
        if (containsCandidate(ordered.subList(0, consciousCap), fallbackSelection.entry.getId())) {
            return new ArrayList<>(ordered.subList(0, consciousCap));
        }
        List<SelectionCandidate> kept = new ArrayList<>();
        kept.add(findCandidate(ordered, fallbackSelection.entry.getId(), fallbackSelection));
        for (SelectionCandidate candidate : ordered) {
            if (kept.size() >= consciousCap) {
                break;
            }
            if (candidate.entry.getId().equals(fallbackSelection.entry.getId())) {
                continue;
            }
            kept.add(candidate);
        }
        return selectTopConsciousCandidates(kept, consciousCap);
    }

    private static boolean containsCandidate(List<SelectionCandidate> candidates, UUID memoryId) {
        if (candidates == null || candidates.isEmpty() || memoryId == null) {
            return false;
        }
        for (SelectionCandidate candidate : candidates) {
            if (candidate != null
                    && candidate.entry != null
                    && memoryId.equals(candidate.entry.getId())) {
                return true;
            }
        }
        return false;
    }

    private static SelectionCandidate findCandidate(List<SelectionCandidate> candidates, UUID memoryId,
            SelectionCandidate fallbackSelection) {
        if (candidates != null && memoryId != null) {
            for (SelectionCandidate candidate : candidates) {
                if (candidate != null
                        && candidate.entry != null
                        && memoryId.equals(candidate.entry.getId())) {
                    return candidate;
                }
            }
        }
        return fallbackSelection;
    }

    private static List<SelectionCandidate> recallAssociationSandbox(List<SelectionCandidate> consciousSelection,
            AgentMemory memory, TagRelationDictionary relationDict, FocusProfile focusProfile, AgentState state,
            long currentGameTime, float wanderingMind, RecallPresetProfile profile,
            List<String> traceLines) {
        if (consciousSelection == null || consciousSelection.isEmpty() || memory == null) {
            return Collections.emptyList();
        }

        List<Seed> sandboxCueInputs = buildAssociationSandboxCueInputs(consciousSelection, profile, wanderingMind);
        if (sandboxCueInputs.isEmpty()) {
            traceLines.add("[Sandbox联想] no sandbox cues");
            return Collections.emptyList();
        }

        Set<UUID> excludedIds = new LinkedHashSet<>();
        for (SelectionCandidate candidate : consciousSelection) {
            if (candidate != null && candidate.entry != null && candidate.entry.getId() != null) {
                excludedIds.add(candidate.entry.getId());
            }
        }

        int sandboxBudget = Math.max(1, Math.min(4, consciousSelection.size()));
        traceLines.add(String.format("[Sandbox联想] source_memories=%d cue_count=%d budget=%d",
                consciousSelection.size(), sandboxCueInputs.size(), sandboxBudget));
        List<MemoryCandidate> candidates = collectLayerCandidates(
                sandboxCueInputs,
                Collections.emptyList(),
                memory,
                excludedIds,
                relationDict,
                focusProfile,
                state,
                currentGameTime);
        if (candidates.isEmpty()) {
            traceLines.add("[Sandbox联想] no associative candidates");
            return Collections.emptyList();
        }

        List<MemoryCandidate> picked = sampleWeightedWithoutReplacement(
                candidates,
                candidate -> candidate.combinedScore,
                Math.min(sandboxBudget, candidates.size()),
                wanderingMind);
        List<SelectionCandidate> selection = new ArrayList<>(picked.size());
        for (MemoryCandidate candidate : picked) {
            SelectionCandidate sandboxCandidate = new SelectionCandidate(candidate.entry);
            sandboxCandidate.absorbPromotedCandidate(candidate, currentGameTime);
            selection.add(sandboxCandidate);
            traceLines.add(String.format(
                    "[Sandbox联想] score=%.2f supports=%d matched=%s memory=%s",
                    candidate.combinedScore,
                    candidate.supportCount,
                    candidate.bestMatchedTag != null ? candidate.bestMatchedTag : "(focus-only)",
                    candidate.entry.getDescription()));
            if (!candidate.supportTags.isEmpty()) {
                traceLines.add("    sandbox_support_tags=" + candidate.supportTags);
            }
        }
        return selectTopCandidates(selection, sandboxBudget);
    }

    private static List<Seed> buildAssociationSandboxCueInputs(List<SelectionCandidate> consciousSelection,
            RecallPresetProfile profile, float wanderingMind) {
        if (consciousSelection == null || consciousSelection.isEmpty()) {
            return Collections.emptyList();
        }

        int tagsPerMemory = Math.max(0, profile.sandboxCuesPerMemory);
        List<Seed> cueInputs = new ArrayList<>();
        for (SelectionCandidate consciousCandidate : consciousSelection) {
            if (consciousCandidate == null || consciousCandidate.entry == null) {
                continue;
            }
            double consciousBaseScore = consciousCandidate.consciousScore > 0.0
                    ? consciousCandidate.consciousScore
                    : consciousCandidate.score;
            List<DetailTag> selectedTags = pickCueTags(
                    consciousCandidate.entry.getAssociativeDetailTags(),
                    tagsPerMemory,
                    wanderingMind);
            for (DetailTag detailTag : selectedTags) {
                cueInputs.add(new Seed(
                        detailTag,
                        consciousCandidate.entry.getFocusTags(),
                        clamp(0.90, 2.40, 1.05 + consciousBaseScore * 0.50)));
            }
        }
        return cueInputs;
    }

    private static FocusProfile buildFocusProfile(List<TaggedContextEntry> visibleContexts, float concentration,
            FocusTag desireTag) {
        if (visibleContexts == null || visibleContexts.isEmpty()) {
            return new FocusProfile(new EnumMap<>(FocusTag.class), null);
        }

        EnumMap<FocusTag, Double> focusMass = new EnumMap<>(FocusTag.class);
        double total = 0.0;

        for (TaggedContextEntry entry : visibleContexts) {
            double base = AttentionFilter.calculateFinalScore(entry, concentration);
            for (FocusTag tag : entry.getFocusTags()) {
                double tagWeight = focusRecallWeight(tag);
                double bonus = (desireTag != null && desireTag == tag)
                        ? getDesireProfileBonusScale() * tagWeight
                        : 0.0;
                double weight = base * tagWeight + bonus;
                focusMass.merge(tag, weight, Double::sum);
                total += weight;
            }
        }

        if (focusMass.isEmpty()) {
            return new FocusProfile(new EnumMap<>(FocusTag.class), null);
        }

        EnumMap<FocusTag, Double> normalized = new EnumMap<>(FocusTag.class);
        FocusTag dominant = null;
        double dominantScore = -1.0;
        for (Map.Entry<FocusTag, Double> entry : focusMass.entrySet()) {
            double normalizedWeight = entry.getValue() / Math.max(0.0001, total);
            normalized.put(entry.getKey(), normalizedWeight);
            if (normalizedWeight > dominantScore) {
                dominant = entry.getKey();
                dominantScore = normalizedWeight;
            }
        }
        return new FocusProfile(normalized, dominant);
    }

    private static List<Seed> buildInitialCueInputs(List<RecallRuntimeState.TagPoolEntry> tagPoolEntries,
            int visibleContextCount, List<String> traceLines) {
        List<Seed> result = new ArrayList<>();
        if (tagPoolEntries == null || tagPoolEntries.isEmpty()) {
            return result;
        }

        double maxTagScore = tagPoolEntries.stream()
                .mapToDouble(entry -> Math.max(0.0, entry.score))
                .max()
                .orElse(0.0);

        traceLines.add(String.format("[记忆起点] visible_contexts=%d active_tag_pool=%d",
                visibleContextCount, tagPoolEntries.size()));

        int previewCount = 0;
        for (RecallRuntimeState.TagPoolEntry tagPoolEntry : tagPoolEntries) {
            DetailTag parsedTag;
            try {
                parsedTag = DetailTag.parse(tagPoolEntry.tag);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            double normalizedScore = maxTagScore > 0.0 ? clamp01(tagPoolEntry.score / maxTagScore) : 0.0;
            double sourceScore = clamp(0.60, 2.00,
                    0.85
                            + normalizedScore * 0.80
                            + Math.min(0.20, Math.max(0, tagPoolEntry.sourceCount - 1) * 0.05)
                            + Math.min(0.25, tagPoolEntry.injectedScoreThisTurn * 0.08));

            Seed seed = new Seed(
                    parsedTag,
                    parseFocusTags(tagPoolEntry.focusTags),
                    sourceScore);
            result.add(seed);
            if (previewCount < INITIAL_SEED_TRACE_PREVIEW_LIMIT) {
                traceLines.add(String.format("  -> pool_tag: %s score=%.2f injected=%.2f sources=%d sourceScore=%.2f",
                        parsedTag, tagPoolEntry.score, tagPoolEntry.injectedScoreThisTurn, tagPoolEntry.sourceCount,
                        sourceScore));
            }
            previewCount++;
        }
        if (result.size() > INITIAL_SEED_TRACE_PREVIEW_LIMIT) {
            traceLines.add(String.format("  -> ... %d more cue_tags",
                    result.size() - INITIAL_SEED_TRACE_PREVIEW_LIMIT));
        }

        return result;
    }

    private static List<MemoryCandidate> collectLayerCandidates(List<Seed> layerSeeds,
            List<RecallRuntimeState.FermentationMemoryEntry> fermentationEntries,
            AgentMemory memory,
            Set<UUID> recalledIds, TagRelationDictionary relationDict, FocusProfile focusProfile,
            AgentState state, long currentGameTime) {
        Map<UUID, MemoryCandidate> candidateMap = new LinkedHashMap<>();
        List<LTMEntry> allMemories = memory.getLtmRecords();
        Map<String, RecallRuntimeState.FermentationMemoryEntry> fermentationByMemoryId = new LinkedHashMap<>();
        if (fermentationEntries != null) {
            for (RecallRuntimeState.FermentationMemoryEntry entry : fermentationEntries) {
                if (entry == null || entry.memoryId == null || entry.memoryId.isBlank()) {
                    continue;
                }
                fermentationByMemoryId.put(entry.memoryId, entry);
            }
        }

        for (Seed seed : layerSeeds) {
            for (LTMEntry entry : allMemories) {
                if (recalledIds.contains(entry.getId())) {
                    continue;
                }

                SemanticMatch semanticMatch = findBestSemanticMatch(seed.tag, entry.getAssociativeDetailTags(), relationDict);
                double memoryFocusAlignment = focusProfile.alignment(entry.getFocusTags());
                double cueFocusAlignment = computeCueFocusAlignment(seed.focusTags, entry.getFocusTags());
                double desiredFocusBonus = computeDesiredFocusBonus(state, entry.getFocusTags());
                double ageFactor = computeAgeFactor(entry, currentGameTime);

                boolean hasSemanticSignal = semanticMatch.score > 0.0;
                boolean hasStrongFocusSignal = memoryFocusAlignment >= 0.38 && cueFocusAlignment >= 0.20;
                if (!hasSemanticSignal && !hasStrongFocusSignal) {
                    continue;
                }

                double semanticFactor = hasSemanticSignal ? (0.50 + semanticMatch.score * 0.50) : 0.32;
                double focusFactor = 0.60
                        + memoryFocusAlignment * 0.70
                        + cueFocusAlignment * 0.25
                        + desiredFocusBonus;
                double strengthFactor = 0.65
                        + entry.getImportance() * 0.35;
                double sourceFactor = 0.60 + clamp01(seed.sourceScore / 2.0) * 0.40;
                double totalScore = semanticFactor
                        * focusFactor
                        * (0.55 + ageFactor * 0.45)
                        * strengthFactor
                        * sourceFactor;

                if (totalScore < 0.22) {
                    continue;
                }

                MemoryCandidate candidate = candidateMap.computeIfAbsent(entry.getId(), id -> new MemoryCandidate(entry));
                candidate.addEvidence(seed, totalScore, semanticMatch.score, memoryFocusAlignment, ageFactor,
                        semanticMatch.tag);
            }
        }

        for (LTMEntry entry : allMemories) {
            if (entry == null || entry.getId() == null || recalledIds.contains(entry.getId())) {
                continue;
            }

            RecallRuntimeState.FermentationMemoryEntry fermentationEntry =
                    fermentationByMemoryId.get(entry.getId().toString());
            if (fermentationEntry == null || fermentationEntry.score <= 0.0) {
                continue;
            }

            double totalScore = fermentationEntry.score;

            if (totalScore < FERMENTATION_RETENTION_SCORE_THRESHOLD) {
                continue;
            }

            MemoryCandidate candidate = candidateMap.computeIfAbsent(entry.getId(), id -> new MemoryCandidate(entry));
            candidate.addFermentationEvidence(
                    fermentationEntry.memoryId,
                    totalScore,
                    fermentationEntry.score,
                    0.0,
                    fermentationEntry.supportTags);
        }

        return new ArrayList<>(candidateMap.values());
    }

    private static List<DetailTag> pickCueTags(Collection<DetailTag> tags, int limit, float wanderingMind) {
        if (tags == null || tags.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<TagSeedCandidate> candidates = new ArrayList<>();
        for (DetailTag tag : tags) {
            candidates.add(new TagSeedCandidate(tag, tagPriority(tag)));
        }
        List<TagSeedCandidate> sampled = sampleWeightedWithoutReplacement(candidates, c -> c.score, limit, wanderingMind);
        List<DetailTag> result = new ArrayList<>();
        for (TagSeedCandidate sampledTag : sampled) {
            result.add(sampledTag.tag);
        }
        return result;
    }

    private static Set<FocusTag> parseFocusTags(Collection<String> rawFocusTags) {
        if (rawFocusTags == null || rawFocusTags.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<FocusTag> parsed = EnumSet.noneOf(FocusTag.class);
        for (String raw : rawFocusTags) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                parsed.add(FocusTag.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return parsed.isEmpty() ? Collections.emptySet() : parsed;
    }

    private static double computeCueFocusAlignment(Collection<FocusTag> cueFocusTags, Collection<FocusTag> memoryFocusTags) {
        if (cueFocusTags == null || cueFocusTags.isEmpty() || memoryFocusTags == null || memoryFocusTags.isEmpty()) {
            return 0.0;
        }
        double overlap = 0.0;
        for (FocusTag tag : cueFocusTags) {
            if (memoryFocusTags.contains(tag)) {
                overlap += focusRecallWeight(tag);
            }
        }
        return clamp01(overlap / Math.max(1.0, cueFocusTags.size()));
    }

    private static double computeDesiredFocusBonus(AgentState state, Collection<FocusTag> memoryFocusTags) {
        if (state == null || state.getCurrentDesireTag() == null || memoryFocusTags == null) {
            return 0.0;
        }
        return memoryFocusTags.contains(state.getCurrentDesireTag())
                ? getDesiredFocusBonusScale() * focusRecallWeight(state.getCurrentDesireTag())
                : 0.0;
    }

    private static Set<UUID> toMemoryIdSet(List<SelectionCandidate> selection) {
        if (selection == null || selection.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (SelectionCandidate candidate : selection) {
            if (candidate != null && candidate.entry != null && candidate.entry.getId() != null) {
                ids.add(candidate.entry.getId());
            }
        }
        return ids;
    }

    private static boolean containsImplantedSelection(List<SelectionCandidate> selection) {
        for (SelectionCandidate candidate : selection) {
            if (candidate != null
                    && candidate.entry != null
                    && candidate.entry.getSourceType() == LTMEntry.SourceType.IMPLANTED
                    && candidate.entry.getOccurredGameTime() < 0L) {
                return true;
            }
        }
        return false;
    }

    private static LTMEntry pickWeightedImplantedFallback(List<Seed> allCueInputsThisTurn, AgentMemory memory,
            Set<UUID> recalledIds, TagRelationDictionary relationDict, float wanderingMind, List<String> traceLines) {
        if (allCueInputsThisTurn == null || allCueInputsThisTurn.isEmpty() || memory == null) {
            return null;
        }

        Map<UUID, ImplantedFallbackCandidate> candidateMap = new LinkedHashMap<>();
        for (LTMEntry entry : memory.getLtmRecords()) {
            if (entry == null
                    || entry.getId() == null
                    || recalledIds.contains(entry.getId())
                    || entry.getSourceType() != LTMEntry.SourceType.IMPLANTED
                    || entry.getOccurredGameTime() >= 0L) {
                continue;
            }

            ImplantedFallbackCandidate candidate = null;
            double recentPenalty = computeRecentRecallPenalty(memory, entry.getId());
            for (Seed cueInput : allCueInputsThisTurn) {
                SemanticMatch match = findBestSemanticMatch(cueInput.tag, entry.getAssociativeDetailTags(), relationDict);
                if (match.score <= 0.0 || match.tag == null) {
                    continue;
                }
                if (candidate == null) {
                    candidate = candidateMap.computeIfAbsent(entry.getId(), ignored -> new ImplantedFallbackCandidate(entry));
                    candidate.recentPenalty = recentPenalty;
                }
                candidate.rawWeight += match.score;
                candidate.hitCount++;
                candidate.matchedSeedTags.add(cueInput.tag.toString());
                candidate.matchedMemoryTags.add(match.tag.toString());
            }
        }

        List<ImplantedFallbackCandidate> candidates = new ArrayList<>();
        for (ImplantedFallbackCandidate candidate : candidateMap.values()) {
            candidate.weight = candidate.rawWeight * candidate.recentPenalty;
            if (candidate.weight > 0.0 && candidate.hitCount > 0) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            traceLines.add(String.format("[植入记忆保底] cue_pool=%d eligible_implanted=0", allCueInputsThisTurn.size()));
            return null;
        }

        List<ImplantedFallbackCandidate> picked = sampleWeightedWithoutReplacement(
                candidates, candidate -> candidate.weight, 1, wanderingMind);
        if (picked.isEmpty()) {
            traceLines.add(String.format("[植入记忆保底] cue_pool=%d weighted_pick_failed", allCueInputsThisTurn.size()));
            return null;
        }

        ImplantedFallbackCandidate winner = picked.get(0);
        traceLines.add(String.format(
                "[植入记忆保底] cue_pool=%d candidates=%d chosen_weight=%.2f raw_weight=%.2f recentPenalty=%.2f hits=%d memory=%s",
                allCueInputsThisTurn.size(),
                candidates.size(),
                winner.weight,
                winner.rawWeight,
                winner.recentPenalty,
                winner.hitCount,
                winner.entry.getDescription()));
        traceLines.add("    fallback_cue_tags=" + winner.matchedSeedTags);
        traceLines.add("    fallback_memory_tags=" + winner.matchedMemoryTags);
        return winner.entry;
    }

    private static double computeAgeFactor(LTMEntry entry, long currentGameTime) {
        if (entry.getSourceType() == LTMEntry.SourceType.IMPLANTED) {
            return 1.0;
        }
        if (currentGameTime < 0L) {
            throw new IllegalStateException(
                    "SYNTHESIZED memory recall requires currentGameTime, but got " + currentGameTime);
        }
        if (entry.getOccurredGameTime() < 0L) {
            throw new IllegalStateException(
                    "SYNTHESIZED memory is missing occurredGameTime: " + entry.getDescription());
        }
        double halfLife = Math.max(1.0, TalkingWeaponConfig.LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE.get());
        double ageDays = Math.max(0.0, (currentGameTime - entry.getOccurredGameTime())
                / (double) dreamdust.talking_weapon.basic_talking_weapon.agent.context.WorldTimeSemantics.TICKS_PER_DAY);
        double decay = Math.exp(-ageDays / halfLife);
        return clamp(0.15, 1.0, decay);
    }

    private static double computeRecentRecallPenalty(AgentMemory memory, UUID memoryId) {
        int remainingCooldown = memory.getRecentRecallCooldown(memoryId);
        if (remainingCooldown <= 0) {
            return 1.0;
        }
        int window = Math.max(1, memory.getRecentRecallWindow());
        int releaseStep = window <= 1
                ? 0
                : Math.max(0, Math.min(window - 1, window - remainingCooldown));
        double ratio = window <= 1
                ? 0.0
                : clamp01(releaseStep / (double) Math.max(1, window - 1));
        double t = clamp01((ratio - RECENT_RECALL_RELEASE_START)
                / Math.max(0.0001, RECENT_RECALL_RELEASE_END - RECENT_RECALL_RELEASE_START));
        return RECENT_RECALL_PENALTY_FLOOR
                + (RECENT_RECALL_PENALTY_CEIL - RECENT_RECALL_PENALTY_FLOOR)
                * Math.pow(t, RECENT_RECALL_CURVE_EXPONENT);
    }

    private static SemanticMatch findBestSemanticMatch(DetailTag seedTag, Collection<DetailTag> memoryTags,
            TagRelationDictionary relationDict) {
        DetailTag bestTag = null;
        double bestScore = 0.0;
        if (memoryTags == null) {
            return new SemanticMatch(null, 0.0);
        }
        for (DetailTag memoryTag : memoryTags) {
            double score = scoreTagAffinity(seedTag, memoryTag, relationDict);
            if (score > bestScore) {
                bestScore = score;
                bestTag = memoryTag;
            }
        }
        return new SemanticMatch(bestTag, bestScore);
    }

    private static double scoreTagAffinity(DetailTag seedTag, DetailTag memoryTag, TagRelationDictionary relationDict) {
        if (seedTag == null || memoryTag == null) {
            return 0.0;
        }
        if (seedTag.equals(memoryTag)) {
            return 1.0;
        }

        Set<String> overlap = new LinkedHashSet<>(relationDict.expand(seedTag.toString()));
        overlap.retainAll(relationDict.expand(memoryTag.toString()));
        if (overlap.isEmpty()) {
            return 0.0;
        }

        boolean overlapHasSpecific = false;
        for (String overlapTag : overlap) {
            if (DetailTag.parse(overlapTag).isSpecific()) {
                overlapHasSpecific = true;
                break;
            }
        }

        if (overlapHasSpecific) {
            return 0.88;
        }
        if (seedTag.isGeneric() && memoryTag.isSpecific()) {
            return 0.70;
        }
        if (seedTag.isSpecific() && memoryTag.isGeneric()) {
            return 0.48;
        }
        return 0.56;
    }

    private static <T> List<T> sampleWeightedWithoutReplacement(List<T> items, ToDoubleFunction<T> scoreFn,
            int limit, float wanderingMind) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }

        List<T> pool = new ArrayList<>(items);
        List<T> result = new ArrayList<>(Math.min(limit, pool.size()));
        while (!pool.isEmpty() && result.size() < limit) {
            double[] weights = new double[pool.size()];
            double total = 0.0;
            for (int i = 0; i < pool.size(); i++) {
                double rawScore = Math.max(0.0001, scoreFn.applyAsDouble(pool.get(i)));
                double weight = applyTemperature(rawScore, wanderingMind);
                weights[i] = weight;
                total += weight;
            }

            if (total <= 0.0) {
                break;
            }

            double roll = ThreadLocalRandom.current().nextDouble(total);
            double cumulative = 0.0;
            int chosenIndex = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                cumulative += weights[i];
                if (roll <= cumulative) {
                    chosenIndex = i;
                    break;
                }
            }
            result.add(pool.remove(chosenIndex));
        }
        return result;
    }

    private static double applyTemperature(double score, float wanderingMind) {
        double exponent = 2.6 - clamp01(wanderingMind) * 2.0; // 低 wandering -> 更尖锐，高 wandering -> 更平坦
        return Math.pow(Math.max(0.0001, score), exponent);
    }

    private static double focusRecallWeight(FocusTag tag) {
        return 1.0;
    }

    private static double getDesireProfileBonusScale() {
        try {
            return TalkingWeaponConfig.LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE.get();
        } catch (Exception e) {
            return DEFAULT_DESIRE_PROFILE_BONUS_SCALE;
        }
    }

    private static double getDesiredFocusBonusScale() {
        try {
            return TalkingWeaponConfig.LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE.get();
        } catch (Exception e) {
            return DEFAULT_DESIRED_FOCUS_BONUS_SCALE;
        }
    }

    private static double tagPriority(DetailTag tag) {
        if (tag == null) {
            return 0.0;
        }
        if (DetailTag.CUSTOM_SPECIFIC.equals(tag.getPrefix())) {
            return 4.0;
        }
        if (DetailTag.NATIVE_SPECIFIC.equals(tag.getPrefix())) {
            return 3.0;
        }
        if (DetailTag.CUSTOM_GENERIC.equals(tag.getPrefix())) {
            return 2.0;
        }
        if (DetailTag.NATIVE_GENERIC.equals(tag.getPrefix())) {
            return 1.0;
        }
        return 0.5;
    }

    private static double clamp01(double value) {
        return clamp(0.0, 1.0, value);
    }

    private static double clamp(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class SemanticMatch {
        final DetailTag tag;
        final double score;

        SemanticMatch(DetailTag tag, double score) {
            this.tag = tag;
            this.score = score;
        }
    }
}
