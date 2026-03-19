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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * 长期记忆召回器：用“注意力方向 + 温度采样 + 分层联想”替代旧的确定性 top-k 检索。
 * <p>
 * 核心思想：
 * <ul>
 *   <li>从当前 visible contexts 中汇总全部可见 detail tags，构成首层 seed 池。</li>
 *   <li>用 wanderingMind 作为随机温度，避免每轮都回想起同一组线索。</li>
 *   <li>用当前 Focus 压力和 concentration 引导联想方向与联想深度。</li>
 *   <li>允许多个 seed 同时指向同一条记忆，并在记忆层聚合证据，而不是过早在 seed 层去重。</li>
 *   <li>用 recent recall 抑制和 nostalgia 时间因子，防止模型反复念同一段往事。</li>
 * </ul>
 */
public final class LTMRecallEngine {

    private static final double COMMON_FOCUS_RECALL_WEIGHT = 0.35;
    private static final double RECENT_RECALL_HARD_SKIP_SEMANTIC_THRESHOLD = 0.88;
    private static final int INITIAL_SEED_TRACE_PREVIEW_LIMIT = 12;

    private LTMRecallEngine() {
    }

    public static final class RecallResult {
        public final List<LTMEntry> recalledMemories;
        public final Set<String> triggerTags;
        public final List<String> traceLines;

        public RecallResult(List<LTMEntry> recalledMemories, Set<String> triggerTags, List<String> traceLines) {
            this.recalledMemories = Collections.unmodifiableList(new ArrayList<>(recalledMemories));
            this.triggerTags = Collections.unmodifiableSet(new LinkedHashSet<>(triggerTags));
            this.traceLines = Collections.unmodifiableList(new ArrayList<>(traceLines));
        }

        public boolean isEmpty() {
            return recalledMemories.isEmpty();
        }
    }

    private static final class RecallProfile {
        final int seedEntryCount;
        final int tagsPerEntry;
        final int memoryPickPerSeed;
        final int maxDepth;
        final int branchPerMemory;
        final int finalMemoryCap;

        private RecallProfile(int seedEntryCount, int tagsPerEntry, int memoryPickPerSeed,
                int maxDepth, int branchPerMemory, int finalMemoryCap) {
            this.seedEntryCount = seedEntryCount;
            this.tagsPerEntry = tagsPerEntry;
            this.memoryPickPerSeed = memoryPickPerSeed;
            this.maxDepth = maxDepth;
            this.branchPerMemory = branchPerMemory;
            this.finalMemoryCap = finalMemoryCap;
        }

        static RecallProfile fromState(AgentState state) {
            AgentState.AssociationMode mode = state != null ? state.getAssociationMode() : null;
            if (mode == null) {
                mode = AgentState.AssociationMode.BALANCED;
            }
            switch (mode) {
                case DIVERGENT:
                    return new RecallProfile(4, 2, 2, 1, 2, 4);
                case DEEP:
                    return new RecallProfile(2, 1, 1, 2, 1, 2);
                case CUSTOM: {
                    AgentState.CustomAssociationConfig customConfig = state != null
                            ? state.getCustomAssociationConfig()
                            : AgentState.CustomAssociationConfig.defaults();
                    return new RecallProfile(
                            customConfig.getSeedEntryCount(),
                            customConfig.getTagsPerEntry(),
                            customConfig.getMemoryPickPerSeed(),
                            customConfig.getMaxDepth(),
                            customConfig.getBranchPerMemory(),
                            customConfig.getFinalMemoryCap());
                }
                case BALANCED:
                default:
                    return new RecallProfile(3, 2, 1, 1, 1, 3);
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
        final int depth;
        final String sourceDescription;
        final String originType;
        final double sourceScore;

        Seed(DetailTag tag, Collection<FocusTag> focusTags, int depth, String sourceDescription,
                String originType, double sourceScore) {
            this.tag = tag;
            this.focusTags = Collections.unmodifiableSet(new LinkedHashSet<>(focusTags));
            this.depth = depth;
            this.sourceDescription = sourceDescription;
            this.originType = originType;
            this.sourceScore = sourceScore;
        }

        String dedupeKey() {
            return depth + "|" + originType + "|" + sourceDescription + "|" + tag;
        }
    }

    private static final class InitialSeedAccumulator {
        final DetailTag tag;
        final Set<FocusTag> focusTags = new LinkedHashSet<>();
        String primarySourceDescription = "";
        int sourceCount;

        InitialSeedAccumulator(DetailTag tag) {
            this.tag = tag;
        }

        void note(TaggedContextEntry entry) {
            if (entry == null) {
                return;
            }
            focusTags.addAll(entry.getFocusTags());
            sourceCount++;
            if (primarySourceDescription.isBlank() && entry.getDescription() != null) {
                primarySourceDescription = entry.getDescription();
            }
        }

        double sourceScore() {
            return 1.0 + Math.min(0.45, Math.max(0, sourceCount - 1) * 0.12);
        }

        String summary() {
            if (sourceCount <= 1) {
                return primarySourceDescription;
            }
            return primarySourceDescription + " +" + (sourceCount - 1);
        }
    }

    private static final class EntrySeedCandidate {
        final TaggedContextEntry entry;
        final double score;

        EntrySeedCandidate(TaggedContextEntry entry, double score) {
            this.entry = entry;
            this.score = score;
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
        double bestRecentPenalty;
        DetailTag bestMatchedTag;
        final Set<String> supportTags = new LinkedHashSet<>();
        final Set<String> supportSources = new LinkedHashSet<>();
        int supportCount;

        MemoryCandidate(LTMEntry entry) {
            this.entry = entry;
        }

        void addEvidence(Seed seed, double score, double semanticScore, double focusAlignment,
                double ageFactor, double recentPenalty, DetailTag matchedTag) {
            supportCount++;
            supportTags.add(seed.tag.toString());
            supportSources.add(seed.originType + ":" + seed.sourceDescription);
            combinedScore += supportCount == 1 ? score : score * 0.55;
            if (score > bestSingleScore) {
                bestSingleScore = score;
                bestSemanticScore = semanticScore;
                bestFocusAlignment = focusAlignment;
                bestAgeFactor = ageFactor;
                bestRecentPenalty = recentPenalty;
                bestMatchedTag = matchedTag;
            }
        }

        void addFocusOnlyEvidence(String sourceDescription, double score, double focusAlignment,
                double ageFactor, double recentPenalty) {
            supportCount++;
            supportSources.add("focus-only:" + sourceDescription);
            combinedScore += supportCount == 1 ? score : score * 0.55;
            if (score > bestSingleScore) {
                bestSingleScore = score;
                bestSemanticScore = 0.0;
                bestFocusAlignment = focusAlignment;
                bestAgeFactor = ageFactor;
                bestRecentPenalty = recentPenalty;
                bestMatchedTag = null;
            }
        }
    }

    private static final class ImplantedFallbackCandidate {
        final LTMEntry entry;
        double rawWeight;
        double weight;
        int hitCount;
        int recentRank = -1;
        double maxSemanticScore;
        double recentPenalty = 1.0;
        final Set<String> matchedSeedTags = new LinkedHashSet<>();
        final Set<String> matchedMemoryTags = new LinkedHashSet<>();

        ImplantedFallbackCandidate(LTMEntry entry) {
            this.entry = entry;
        }
    }

    public static RecallResult recall(List<TaggedContextEntry> visibleContexts, AgentMemory memory, AgentState state,
            long currentGameTime) {
        if (visibleContexts == null || visibleContexts.isEmpty() || memory == null || memory.getLtmRecords().isEmpty()) {
            return new RecallResult(Collections.emptyList(), Collections.emptySet(), Collections.emptyList());
        }

        RecallProfile profile = RecallProfile.fromState(state);
        if (profile.finalMemoryCap <= 0) {
            return new RecallResult(Collections.emptyList(), Collections.emptySet(), Collections.emptyList());
        }

        memory.setRecentRecallWindow(TalkingWeaponConfig.LTM_RECALL_RECENT_WINDOW.get());

        float concentration = state != null ? state.getConcentration() : 0.5f;
        float wanderingMind = state != null ? state.getWanderingMind() : 0.1f;
        float nostalgia = state != null ? state.getNostalgia() : 0.5f;
        FocusTag desireTag = state != null ? state.getCurrentDesireTag() : null;
        long now = System.currentTimeMillis();

        FocusProfile focusProfile = buildFocusProfile(visibleContexts, concentration, desireTag);
        List<String> traceLines = new ArrayList<>();
        if (focusProfile.dominantFocus != null) {
            traceLines.add("[注意力方向] dominant_focus=" + focusProfile.dominantFocus.name());
        }

        List<Seed> frontier = buildInitialSeeds(visibleContexts, focusProfile, state, profile, traceLines);
        if (frontier.isEmpty()) {
            traceLines.add("[记忆起点] no detail-tag seeds, recall aborted");
            return new RecallResult(Collections.emptyList(), Collections.emptySet(), traceLines);
        }

        List<LTMEntry> recalled = new ArrayList<>();
        Set<String> triggerTags = new LinkedHashSet<>();
        Set<UUID> recalledIds = new LinkedHashSet<>();
        Set<String> seenSeedKeys = new LinkedHashSet<>();
        // frontier 表示“当前联想层正在向外扩展的 seed 列表”：
        // depth=0 时是由 visible contexts 聚合出的首层 detail tags，
        // 后续层则来自上一层被选中记忆继续派生出的 associative tags。
        List<Seed> totalSeedPool = new ArrayList<>();
        TagRelationDictionary relationDict = TagRelationDictionary.getInstance();

        for (int depth = 0; depth <= profile.maxDepth
                && !frontier.isEmpty()
                && recalled.size() < profile.finalMemoryCap; depth++) {

            List<Seed> layerSeeds = new ArrayList<>();
            for (Seed seed : frontier) {
                if (seenSeedKeys.add(seed.dedupeKey())) {
                    layerSeeds.add(seed);
                    triggerTags.add(seed.tag.toString());
                }
            }
            if (layerSeeds.isEmpty()) {
                break;
            }
            totalSeedPool.addAll(layerSeeds);

            traceLines.add(String.format("[联想层 %d] frontier=%d", depth, layerSeeds.size()));
            List<MemoryCandidate> candidates = collectLayerCandidates(
                    layerSeeds, memory, recalledIds, relationDict, focusProfile, state, now, nostalgia, currentGameTime);
            if (candidates.isEmpty()) {
                traceLines.add(String.format("[联想层 %d] no candidate memories", depth));
                break;
            }

            int remainingSlots = profile.finalMemoryCap - recalled.size();
            int layerBudget = Math.min(remainingSlots,
                    Math.max(1, Math.min(candidates.size(), layerSeeds.size() * profile.memoryPickPerSeed)));
            List<MemoryCandidate> picked = sampleWeightedWithoutReplacement(
                    candidates, c -> c.combinedScore, layerBudget, wanderingMind);

            List<Seed> nextFrontier = new ArrayList<>();
            for (MemoryCandidate candidate : picked) {
                if (!recalledIds.add(candidate.entry.getId())) {
                    continue;
                }
                recalled.add(candidate.entry);
                candidate.entry.markAccessed(now);
                if (candidate.bestMatchedTag != null) {
                    triggerTags.add(candidate.bestMatchedTag.toString());
                }

                traceLines.add(String.format(
                        "[记忆召回] depth=%d score=%.2f supports=%d matched=%s semantic=%.2f focus=%.2f recentPenalty=%.2f ageFactor=%.2f memory=%s",
                        depth,
                        candidate.combinedScore,
                        candidate.supportCount,
                        candidate.bestMatchedTag != null ? candidate.bestMatchedTag : "(focus-only)",
                        candidate.bestSemanticScore,
                        candidate.bestFocusAlignment,
                        candidate.bestRecentPenalty,
                        candidate.bestAgeFactor,
                        candidate.entry.getDescription()));
                if (!candidate.supportTags.isEmpty()) {
                    traceLines.add("    support_tags=" + candidate.supportTags);
                }

                if (depth >= profile.maxDepth || profile.branchPerMemory <= 0) {
                    continue;
                }

                double continueChance = computeContinueChance(candidate.bestFocusAlignment, concentration, desireTag,
                        candidate.entry.getFocusTags());
                if (ThreadLocalRandom.current().nextDouble() > continueChance) {
                    traceLines.add(String.format("    branch_skipped(chance=%.2f)", continueChance));
                    continue;
                }

                int branchCount = computeBranchCount(profile.branchPerMemory, candidate.bestFocusAlignment, concentration);
                List<DetailTag> branchTags = pickSeedTags(candidate.entry.getAssociativeDetailTags(), branchCount, wanderingMind);
                if (branchTags.isEmpty()) {
                    traceLines.add("    branch_skipped(no associative tags)");
                    continue;
                }

                for (DetailTag branchTag : branchTags) {
                    nextFrontier.add(new Seed(
                            branchTag,
                            candidate.entry.getFocusTags(),
                            depth + 1,
                            candidate.entry.getDescription(),
                            "memory",
                            candidate.combinedScore));
                    traceLines.add("    => branch_seed: " + branchTag);
                }
            }

            frontier = nextFrontier;
        }

        if (state != null
                && state.isImplantedMemorySafetyNetEnabled()
                && !containsImplantedRecall(recalled)
                && !totalSeedPool.isEmpty()) {
            LTMEntry implantedFallback = pickWeightedImplantedFallback(
                    totalSeedPool, memory, recalledIds, relationDict, wanderingMind, traceLines);
            if (implantedFallback != null && recalledIds.add(implantedFallback.getId())) {
                recalled.add(implantedFallback);
                implantedFallback.markAccessed(now);
            }
        }

        memory.noteRecalledMemories(recalled);
        return new RecallResult(recalled, triggerTags, traceLines);
    }

    private static FocusProfile buildFocusProfile(List<TaggedContextEntry> visibleContexts, float concentration,
            FocusTag desireTag) {
        EnumMap<FocusTag, Double> focusMass = new EnumMap<>(FocusTag.class);
        double total = 0.0;

        for (TaggedContextEntry entry : visibleContexts) {
            double base = AttentionFilter.calculateFinalScore(entry, concentration);
            for (FocusTag tag : entry.getFocusTags()) {
                double tagWeight = focusRecallWeight(tag);
                double bonus = (desireTag != null && desireTag == tag) ? 0.25 * tagWeight : 0.0;
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

    private static List<Seed> buildInitialSeeds(List<TaggedContextEntry> visibleContexts, FocusProfile focusProfile,
            AgentState state, RecallProfile profile, List<String> traceLines) {
        float concentration = state != null ? state.getConcentration() : 0.5f;
        float wanderingMind = state != null ? state.getWanderingMind() : 0.1f;

        LinkedHashMap<String, InitialSeedAccumulator> accumulators = new LinkedHashMap<>();
        for (TaggedContextEntry entry : visibleContexts) {
            if (entry.getDetailTags().isEmpty()) {
                continue;
            }
            for (DetailTag tag : entry.getDetailTags()) {
                InitialSeedAccumulator accumulator = accumulators.computeIfAbsent(
                        tag.toString(), ignored -> new InitialSeedAccumulator(tag));
                accumulator.note(entry);
            }
        }

        List<Seed> result = new ArrayList<>();
        if (accumulators.isEmpty()) {
            return result;
        }

        List<InitialSeedAccumulator> orderedSeeds = new ArrayList<>(accumulators.values());
        orderedSeeds.sort((left, right) -> {
            int bySupport = Integer.compare(right.sourceCount, left.sourceCount);
            if (bySupport != 0) {
                return bySupport;
            }
            int byPriority = Double.compare(tagPriority(right.tag), tagPriority(left.tag));
            if (byPriority != 0) {
                return byPriority;
            }
            return left.tag.toString().compareTo(right.tag.toString());
        });

        traceLines.add(String.format("[记忆起点] visible_contexts=%d unique_seed_tags=%d",
                visibleContexts.size(), orderedSeeds.size()));

        int previewCount = 0;
        for (InitialSeedAccumulator accumulator : orderedSeeds) {
            Seed seed = new Seed(
                    accumulator.tag,
                    accumulator.focusTags,
                    0,
                    accumulator.summary(),
                    "visible_tags",
                    accumulator.sourceScore());
            result.add(seed);
            if (previewCount < INITIAL_SEED_TRACE_PREVIEW_LIMIT) {
                traceLines.add(String.format("  -> seed_tag: %s supports=%d sourceScore=%.2f",
                        accumulator.tag, accumulator.sourceCount, accumulator.sourceScore()));
            }
            previewCount++;
        }
        if (orderedSeeds.size() > INITIAL_SEED_TRACE_PREVIEW_LIMIT) {
            traceLines.add(String.format("  -> ... %d more seed_tags",
                    orderedSeeds.size() - INITIAL_SEED_TRACE_PREVIEW_LIMIT));
        }

        return result;
    }

    private static List<MemoryCandidate> collectLayerCandidates(List<Seed> layerSeeds, AgentMemory memory,
            Set<UUID> recalledIds, TagRelationDictionary relationDict, FocusProfile focusProfile,
            AgentState state, long now, float nostalgia, long currentGameTime) {
        Map<UUID, MemoryCandidate> candidateMap = new LinkedHashMap<>();
        List<LTMEntry> allMemories = memory.getLtmRecords();

        for (Seed seed : layerSeeds) {
            for (LTMEntry entry : allMemories) {
                if (recalledIds.contains(entry.getId())) {
                    continue;
                }

                SemanticMatch semanticMatch = findBestSemanticMatch(seed.tag, entry.getAssociativeDetailTags(), relationDict);
                int recentRank = memory.getRecentRecallRank(entry.getId());
                if (recentRank >= 0
                        && recentRank <= 1
                        && semanticMatch.score < RECENT_RECALL_HARD_SKIP_SEMANTIC_THRESHOLD) {
                    continue;
                }
                double memoryFocusAlignment = focusProfile.alignment(entry.getFocusTags());
                double seedFocusAlignment = computeSeedFocusAlignment(seed.focusTags, entry.getFocusTags());
                double desiredFocusBonus = computeDesiredFocusBonus(state, entry.getFocusTags());
                double ageFactor = computeAgeFactor(entry, nostalgia, currentGameTime, now);
                double recentPenalty = computeRecentRecallPenalty(memory, entry.getId());

                boolean hasSemanticSignal = semanticMatch.score > 0.0;
                boolean hasStrongFocusSignal = memoryFocusAlignment >= 0.38 && seedFocusAlignment >= 0.20;
                if (!hasSemanticSignal && !hasStrongFocusSignal) {
                    continue;
                }

                double semanticFactor = hasSemanticSignal ? (0.50 + semanticMatch.score * 0.50) : 0.32;
                double focusFactor = 0.60
                        + memoryFocusAlignment * 0.70
                        + seedFocusAlignment * 0.25
                        + desiredFocusBonus;
                double strengthFactor = 0.65
                        + entry.getImportance() * 0.20
                        + entry.getClarity() * 0.15;
                double sourceFactor = 0.60 + clamp01(seed.sourceScore / 2.0) * 0.40;
                double totalScore = semanticFactor
                        * focusFactor
                        * (0.55 + ageFactor * 0.45)
                        * recentPenalty
                        * strengthFactor
                        * sourceFactor;

                if (totalScore < 0.22) {
                    continue;
                }

                MemoryCandidate candidate = candidateMap.computeIfAbsent(entry.getId(), id -> new MemoryCandidate(entry));
                candidate.addEvidence(seed, totalScore, semanticMatch.score, memoryFocusAlignment, ageFactor,
                        recentPenalty, semanticMatch.tag);
            }
        }

        return new ArrayList<>(candidateMap.values());
    }

    private static List<DetailTag> pickSeedTags(Collection<DetailTag> tags, int limit, float wanderingMind) {
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

    private static double computeSeedFocusAlignment(Collection<FocusTag> seedFocusTags, Collection<FocusTag> memoryFocusTags) {
        if (seedFocusTags == null || seedFocusTags.isEmpty() || memoryFocusTags == null || memoryFocusTags.isEmpty()) {
            return 0.0;
        }
        double overlap = 0.0;
        for (FocusTag tag : seedFocusTags) {
            if (memoryFocusTags.contains(tag)) {
                overlap += focusRecallWeight(tag);
            }
        }
        return clamp01(overlap / Math.max(1.0, seedFocusTags.size()));
    }

    private static double computeDesiredFocusBonus(AgentState state, Collection<FocusTag> memoryFocusTags) {
        if (state == null || state.getCurrentDesireTag() == null || memoryFocusTags == null) {
            return 0.0;
        }
        return memoryFocusTags.contains(state.getCurrentDesireTag())
                ? 0.18 * focusRecallWeight(state.getCurrentDesireTag())
                : 0.0;
    }

    private static double computeContinueChance(double focusAlignment, float concentration,
            FocusTag desireTag, Collection<FocusTag> memoryFocusTags) {
        double chance = 0.15 + concentration * 0.45 + focusAlignment * 0.30;
        if (desireTag != null && memoryFocusTags != null && memoryFocusTags.contains(desireTag)) {
            chance += 0.10 * focusRecallWeight(desireTag);
        }
        return clamp(0.10, 0.95, chance);
    }

    private static boolean containsImplantedRecall(List<LTMEntry> recalled) {
        for (LTMEntry entry : recalled) {
            if (entry != null
                    && entry.getSourceType() == LTMEntry.SourceType.IMPLANTED
                    && entry.getOccurredGameTime() < 0L) {
                return true;
            }
        }
        return false;
    }

    private static LTMEntry pickWeightedImplantedFallback(List<Seed> totalSeedPool, AgentMemory memory,
            Set<UUID> recalledIds, TagRelationDictionary relationDict, float wanderingMind, List<String> traceLines) {
        if (totalSeedPool == null || totalSeedPool.isEmpty() || memory == null) {
            return null;
        }

        Map<UUID, ImplantedFallbackCandidate> candidateMap = new LinkedHashMap<>();
        for (LTMEntry entry : memory.getLtmRecords()) {
            int recentRank = memory.getRecentRecallRank(entry.getId());
            if (entry == null
                    || entry.getId() == null
                    || recalledIds.contains(entry.getId())
                    || entry.getSourceType() != LTMEntry.SourceType.IMPLANTED
                    || entry.getOccurredGameTime() >= 0L) {
                continue;
            }

            ImplantedFallbackCandidate candidate = null;
            double recentPenalty = computeRecentRecallPenalty(memory, entry.getId());
            for (Seed seed : totalSeedPool) {
                SemanticMatch match = findBestSemanticMatch(seed.tag, entry.getAssociativeDetailTags(), relationDict);
                if (match.score <= 0.0 || match.tag == null) {
                    continue;
                }
                if (candidate == null) {
                    candidate = candidateMap.computeIfAbsent(entry.getId(), ignored -> new ImplantedFallbackCandidate(entry));
                    candidate.recentPenalty = recentPenalty;
                    candidate.recentRank = recentRank;
                }
                candidate.rawWeight += match.score;
                candidate.hitCount++;
                candidate.maxSemanticScore = Math.max(candidate.maxSemanticScore, match.score);
                candidate.matchedSeedTags.add(seed.tag.toString());
                candidate.matchedMemoryTags.add(match.tag.toString());
            }
        }

        List<ImplantedFallbackCandidate> candidates = new ArrayList<>();
        for (ImplantedFallbackCandidate candidate : candidateMap.values()) {
            if (candidate.recentRank >= 0
                    && candidate.recentRank <= 1
                    && candidate.maxSemanticScore < RECENT_RECALL_HARD_SKIP_SEMANTIC_THRESHOLD) {
                continue;
            }
            candidate.weight = candidate.rawWeight * candidate.recentPenalty;
            if (candidate.weight > 0.0 && candidate.hitCount > 0) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            traceLines.add(String.format("[植入记忆保底] seed_pool=%d eligible_implanted=0", totalSeedPool.size()));
            return null;
        }

        List<ImplantedFallbackCandidate> picked = sampleWeightedWithoutReplacement(
                candidates, candidate -> candidate.weight, 1, wanderingMind);
        if (picked.isEmpty()) {
            traceLines.add(String.format("[植入记忆保底] seed_pool=%d weighted_pick_failed", totalSeedPool.size()));
            return null;
        }

        ImplantedFallbackCandidate winner = picked.get(0);
        traceLines.add(String.format(
                "[植入记忆保底] seed_pool=%d candidates=%d chosen_weight=%.2f raw_weight=%.2f recentPenalty=%.2f hits=%d memory=%s",
                totalSeedPool.size(),
                candidates.size(),
                winner.weight,
                winner.rawWeight,
                winner.recentPenalty,
                winner.hitCount,
                winner.entry.getDescription()));
        traceLines.add("    fallback_seed_tags=" + winner.matchedSeedTags);
        traceLines.add("    fallback_memory_tags=" + winner.matchedMemoryTags);
        return winner.entry;
    }

    private static int computeBranchCount(int branchPerMemory, double focusAlignment, float concentration) {
        if (branchPerMemory <= 0) {
            return 0;
        }
        double factor = 0.45 + focusAlignment * 0.35 + concentration * 0.20;
        int branchCount = (int) Math.round(branchPerMemory * factor);
        return Math.max(1, Math.min(branchPerMemory, branchCount));
    }

    private static double computeAgeFactor(LTMEntry entry, float nostalgia, long currentGameTime, long now) {
        if (entry.getSourceType() == LTMEntry.SourceType.IMPLANTED) {
            return 1.0;
        }
        if (currentGameTime > 0L && entry.getOccurredGameTime() >= 0L) {
            double ageDays = Math.max(0.0, (currentGameTime - entry.getOccurredGameTime())
                    / (double) dreamdust.talking_weapon.basic_talking_weapon.agent.context.WorldTimeSemantics.TICKS_PER_DAY);
            double halfLifeDays = 6.0 + nostalgia * 114.0;
            double decay = Math.exp(-ageDays / Math.max(1.0, halfLifeDays));
            return clamp(0.15, 1.0, decay);
        }
        if (entry.getTimestamp() <= 0L) {
            return 1.0;
        }
        double ageHours = Math.max(0.0, (now - entry.getTimestamp()) / 3_600_000.0);
        double halfLifeHours = 6.0 + nostalgia * 114.0;
        double decay = Math.exp(-ageHours / Math.max(1.0, halfLifeHours));
        return clamp(0.15, 1.0, decay);
    }

    private static double computeRecentRecallPenalty(AgentMemory memory, UUID memoryId) {
        int rank = memory.getRecentRecallRank(memoryId);
        if (rank < 0) {
            return 1.0;
        }
        if (rank == 0) {
            return 0.01;
        }
        if (rank == 1) {
            return 0.03;
        }
        if (rank == 2) {
            return 0.07;
        }
        if (rank == 3) {
            return 0.14;
        }
        if (rank == 4) {
            return 0.24;
        }
        int window = Math.max(1, memory.getRecentRecallWindow());
        double ratio = rank / (double) window;
        return clamp(0.24, 0.86, 0.24 + Math.pow(clamp01(ratio), 1.8) * 0.62);
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
        return tag == FocusTag.COMMON ? COMMON_FOCUS_RECALL_WEIGHT : 1.0;
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
