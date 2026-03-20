package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AttentionFilter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.WorldTimeSemantics;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一记忆召回模型的跨轮运行时状态。
 * <p>
 * 这个对象只负责维护统一模型中的四层状态：
 * <ul>
 * <li>tag_pool</li>
 * <li>fermentation_buffer</li>
 * <li>conscious_memories</li>
 * <li>association_sandbox</li>
 * </ul>
 * <p>
 * recall 算法本身位于 {@link LTMRecallEngine}；这里负责跨轮衰减、主池写入和结果写回。
 */
public final class RecallRuntimeState {

    private static final String CAPTURE_MODE_UNIFIED_RUNTIME = "unified-runtime-v1";
    private static final double TAG_POOL_EPSILON = 0.035;
    private static final double FERMENTATION_EPSILON = 0.06;
    private static final int TAG_POOL_CAP = 96;
    private static final int FERMENTATION_BUFFER_CAP = 32;
    private static final int ASSOCIATION_SANDBOX_CUE_CAP = 24;
    private static final double MAX_INTERVAL_BONUS = 2.60;
    private static final double FERMENTATION_CUE_BOOST_FACTOR = 0.16;
    private static final double FERMENTATION_CUE_BOOST_CAP = 0.85;

    private long turnCounter;
    private long currentGameTime = -1L;
    private long previousGameTime = -1L;
    private long deltaGameTime;
    private long wallClockTime = -1L;
    private int visibleContextCount;
    private int recalledMemoryCount;

    private final LinkedHashMap<String, TagPoolEntry> tagPool = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, FermentationMemoryEntry> fermentationBuffer = new LinkedHashMap<>();
    private final List<ConsciousMemoryEntry> consciousMemories = new ArrayList<>();
    private final AssociationSandbox associationSandbox = new AssociationSandbox();

    public synchronized void beginTurn(List<TaggedContextEntry> visibleContexts, AgentState state, long currentGameTime) {
        long lastGameTime = this.currentGameTime;
        this.previousGameTime = lastGameTime;
        this.currentGameTime = currentGameTime;
        this.deltaGameTime = lastGameTime >= 0L && currentGameTime >= 0L
                ? Math.max(0L, currentGameTime - lastGameTime)
                : 0L;
        this.wallClockTime = System.currentTimeMillis();
        this.visibleContextCount = visibleContexts != null ? visibleContexts.size() : 0;
        this.recalledMemoryCount = 0;
        this.turnCounter++;

        clearTurnOnlyLayers();
        resetPerTurnAnnotations();
        decayTagPool(deltaGameTime);
        decayFermentationBuffer(deltaGameTime);
        injectVisibleContexts(visibleContexts, state, currentGameTime, intervalBonus(deltaGameTime));
        applyTagPoolCueBoostToFermentation(currentGameTime);
        trimTagPool();
        trimFermentationBuffer();
    }

    public synchronized Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.captureMode = CAPTURE_MODE_UNIFIED_RUNTIME;
        snapshot.turnCounter = turnCounter;
        snapshot.currentGameTime = currentGameTime;
        snapshot.previousGameTime = previousGameTime;
        snapshot.deltaGameTime = deltaGameTime;
        snapshot.wallClockTime = wallClockTime;
        snapshot.visibleContextCount = visibleContextCount;
        snapshot.recalledMemoryCount = recalledMemoryCount;

        List<TagPoolEntry> orderedTags = new ArrayList<>(tagPool.values());
        orderedTags.sort(Comparator
                .comparingDouble((TagPoolEntry entry) -> entry.score).reversed()
                .thenComparing(entry -> entry.tag));
        for (TagPoolEntry entry : orderedTags) {
            snapshot.tagPool.add(copyTagPoolEntry(entry));
        }
        List<FermentationMemoryEntry> orderedFermentation = new ArrayList<>(fermentationBuffer.values());
        orderedFermentation.sort(Comparator
                .comparingDouble((FermentationMemoryEntry entry) -> entry.score).reversed()
                .thenComparing(entry -> entry.memoryId));
        for (FermentationMemoryEntry entry : orderedFermentation) {
            snapshot.fermentationBuffer.add(copyFermentationEntry(entry));
        }
        for (ConsciousMemoryEntry entry : consciousMemories) {
            snapshot.consciousMemories.add(copyConsciousEntry(entry));
        }
        snapshot.associationSandbox = copyAssociationSandbox(associationSandbox);
        return snapshot;
    }

    public synchronized List<TagPoolEntry> snapshotTagPool() {
        List<TagPoolEntry> orderedTags = new ArrayList<>(tagPool.values());
        orderedTags.sort(Comparator
                .comparingDouble((TagPoolEntry entry) -> entry.score).reversed()
                .thenComparing(entry -> entry.tag));
        List<TagPoolEntry> copies = new ArrayList<>(orderedTags.size());
        for (TagPoolEntry entry : orderedTags) {
            copies.add(copyTagPoolEntry(entry));
        }
        return copies;
    }

    public synchronized List<FermentationMemoryEntry> snapshotFermentationBuffer() {
        List<FermentationMemoryEntry> orderedEntries = new ArrayList<>(fermentationBuffer.values());
        orderedEntries.sort(Comparator
                .comparingDouble((FermentationMemoryEntry entry) -> entry.score).reversed()
                .thenComparing(entry -> entry.memoryId));
        List<FermentationMemoryEntry> copies = new ArrayList<>(orderedEntries.size());
        for (FermentationMemoryEntry entry : orderedEntries) {
            copies.add(copyFermentationEntry(entry));
        }
        return copies;
    }

    /**
     * 写回主 recall 的结果：
     * retained fermentation 会替换主发酵层，
     * conscious memories 会重建表层意识与 sandbox 输入层。
     */
    public synchronized void applySelectionOutcome(List<FermentationMemoryEntry> retainedFermentationEntries,
            Collection<ConsciousMemoryEntry> consciousEntries) {
        applySelectionOutcome(retainedFermentationEntries, consciousEntries,
                consciousEntries != null ? consciousEntries.size() : 0);
    }

    public synchronized void applySelectionOutcome(List<FermentationMemoryEntry> retainedFermentationEntries,
            Collection<ConsciousMemoryEntry> consciousEntries, int promptMemoryCount) {
        fermentationBuffer.clear();
        if (retainedFermentationEntries != null) {
            for (FermentationMemoryEntry entry : retainedFermentationEntries) {
                if (entry == null || entry.memoryId == null || entry.memoryId.isBlank()) {
                    continue;
                }
                try {
                    fermentationBuffer.put(UUID.fromString(entry.memoryId), copyFermentationEntry(entry));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        rebuildConsciousMemories(consciousEntries);
        rebuildAssociationSandboxFromConscious();
        recalledMemoryCount = Math.max(0, promptMemoryCount);
    }

    /**
     * 补写本轮 sandbox recall 的产物。
     * <p>
     * 这些记忆只加入 `association_sandbox.memoryIds`，不会反向污染主发酵层。
     */
    public synchronized void applyAssociationSandboxOutcome(Collection<String> associatedMemoryIds) {
        if (associatedMemoryIds == null || associatedMemoryIds.isEmpty()) {
            return;
        }
        for (String memoryId : associatedMemoryIds) {
            if (memoryId == null || memoryId.isBlank() || associationSandbox.memoryIds.contains(memoryId)) {
                continue;
            }
            associationSandbox.memoryIds.add(memoryId);
        }
        associationSandbox.active = !associationSandbox.cueTags.isEmpty() || !associationSandbox.memoryIds.isEmpty();
    }

    private void clearTurnOnlyLayers() {
        consciousMemories.clear();
        associationSandbox.clear();
    }

    private void resetPerTurnAnnotations() {
        for (TagPoolEntry entry : tagPool.values()) {
            entry.newlyInjectedThisTurn = false;
            entry.injectedScoreThisTurn = 0.0;
        }
        for (FermentationMemoryEntry entry : fermentationBuffer.values()) {
            entry.enteredConsciousThisTurn = false;
            entry.cueBoostThisTurn = 0.0;
            entry.supportTags.clear();
        }
    }

    private void decayTagPool(long deltaGameTime) {
        if (deltaGameTime <= 0L || tagPool.isEmpty()) {
            return;
        }
        double factor = decayFactor(deltaGameTime, resolveTagPoolHalfLifeTicks());
        Iterator<Map.Entry<String, TagPoolEntry>> iterator = tagPool.entrySet().iterator();
        while (iterator.hasNext()) {
            TagPoolEntry entry = iterator.next().getValue();
            entry.score *= factor;
            if (entry.score < TAG_POOL_EPSILON) {
                iterator.remove();
            }
        }
    }

    private void decayFermentationBuffer(long deltaGameTime) {
        if (deltaGameTime <= 0L || fermentationBuffer.isEmpty()) {
            return;
        }
        double factor = decayFactor(deltaGameTime, resolveFermentationHalfLifeTicks());
        Iterator<Map.Entry<UUID, FermentationMemoryEntry>> iterator = fermentationBuffer.entrySet().iterator();
        while (iterator.hasNext()) {
            FermentationMemoryEntry entry = iterator.next().getValue();
            entry.score *= factor;
            if (entry.score < FERMENTATION_EPSILON) {
                iterator.remove();
            }
        }
    }

    private void injectVisibleContexts(List<TaggedContextEntry> visibleContexts, AgentState state,
            long currentGameTime, double intervalBonus) {
        if (visibleContexts == null || visibleContexts.isEmpty()) {
            return;
        }

        float concentration = state != null ? state.getConcentration() : 0.5f;
        Map<String, TagPoolAccumulator> accumulators = new LinkedHashMap<>();

        for (TaggedContextEntry entry : visibleContexts) {
            if (entry == null || entry.getDetailTags().isEmpty()) {
                continue;
            }
            double entryScore = Math.max(0.01, AttentionFilter.calculateFinalScore(entry, concentration));
            for (DetailTag tag : entry.getDetailTags()) {
                if (tag == null) {
                    continue;
                }
                TagPoolAccumulator accumulator = accumulators.computeIfAbsent(
                        tag.toString(), ignored -> new TagPoolAccumulator(tag));
                accumulator.score += entryScore;
                accumulator.sourceCount++;
                accumulator.focusTags.addAll(toFocusTagNames(entry.getFocusTags()));
            }
        }

        List<TagPoolAccumulator> ordered = new ArrayList<>(accumulators.values());
        ordered.sort((left, right) -> {
            int byScore = Double.compare(right.score, left.score);
            if (byScore != 0) {
                return byScore;
            }
            int bySourceCount = Integer.compare(right.sourceCount, left.sourceCount);
            if (bySourceCount != 0) {
                return bySourceCount;
            }
            return left.tag.toString().compareTo(right.tag.toString());
        });

        for (TagPoolAccumulator accumulator : ordered) {
            TagPoolEntry tagEntry = new TagPoolEntry();
            tagEntry.tag = accumulator.tag.toString();
            TagPoolEntry existing = tagPool.get(tagEntry.tag);
            double injectedScore = accumulator.score * intervalBonus;
            if (existing == null) {
                tagEntry.score = injectedScore;
                tagEntry.sourceCount = accumulator.sourceCount;
                tagEntry.focusTags.addAll(accumulator.focusTags);
                tagEntry.lastInjectedGameTime = currentGameTime;
                tagEntry.lastMatchedGameTime = currentGameTime;
                tagEntry.newlyInjectedThisTurn = true;
                tagEntry.injectedScoreThisTurn = injectedScore;
                tagPool.put(tagEntry.tag, tagEntry);
            } else {
                existing.score += injectedScore;
                existing.sourceCount += accumulator.sourceCount;
                mergeUnique(existing.focusTags, accumulator.focusTags);
                existing.lastInjectedGameTime = currentGameTime;
                existing.lastMatchedGameTime = currentGameTime;
                existing.newlyInjectedThisTurn = true;
                existing.injectedScoreThisTurn += injectedScore;
            }
        }
    }

    private void applyTagPoolCueBoostToFermentation(long currentGameTime) {
        if (tagPool.isEmpty() || fermentationBuffer.isEmpty()) {
            return;
        }

        for (FermentationMemoryEntry entry : fermentationBuffer.values()) {
            double cueBoost = 0.0;
            for (String tag : entry.associativeTags) {
                TagPoolEntry poolEntry = tagPool.get(tag);
                if (poolEntry == null) {
                    continue;
                }
                cueBoost += poolEntry.score * FERMENTATION_CUE_BOOST_FACTOR;
                if (!entry.supportTags.contains(tag)) {
                    entry.supportTags.add(tag);
                }
                poolEntry.lastMatchedGameTime = currentGameTime;
            }
            entry.cueBoostThisTurn = Math.min(FERMENTATION_CUE_BOOST_CAP, cueBoost);
            if (entry.cueBoostThisTurn > 0.0) {
                entry.score = clamp(0.0, 3.0, entry.score + entry.cueBoostThisTurn);
                entry.lastUpdatedGameTime = currentGameTime;
            }
        }
    }

    private void rebuildConsciousMemories(Collection<ConsciousMemoryEntry> consciousEntries) {
        consciousMemories.clear();
        if (consciousEntries == null || consciousEntries.isEmpty()) {
            return;
        }

        for (ConsciousMemoryEntry source : consciousEntries) {
            if (source == null || source.memoryId == null || source.memoryId.isBlank()) {
                continue;
            }

            ConsciousMemoryEntry consciousEntry = new ConsciousMemoryEntry();
            consciousEntry.memoryId = source.memoryId;
            consciousEntry.description = source.description;
            consciousEntry.score = source.score;
            consciousEntry.sourceType = source.sourceType;
            consciousEntry.focusTags.addAll(source.focusTags);
            consciousEntry.supportTags.addAll(source.supportTags);
            consciousEntry.associativeTags.addAll(source.associativeTags);
            consciousMemories.add(consciousEntry);
        }
    }

    private void rebuildAssociationSandboxFromConscious() {
        associationSandbox.clear();
        associationSandbox.hopLimit = 1;
        associationSandbox.recallBudget = Math.max(0, Math.min(4, consciousMemories.size()));

        for (ConsciousMemoryEntry consciousEntry : consciousMemories) {
            if (consciousEntry == null || consciousEntry.memoryId == null || consciousEntry.memoryId.isBlank()) {
                continue;
            }
            String memoryId = consciousEntry.memoryId;
            associationSandbox.sourceMemoryIds.add(memoryId);
            associationSandbox.memoryIds.add(memoryId);
            for (String tag : consciousEntry.associativeTags) {
                appendUniqueCapped(associationSandbox.cueTags, safe(tag), ASSOCIATION_SANDBOX_CUE_CAP);
            }
        }

        associationSandbox.active = !associationSandbox.cueTags.isEmpty() || !associationSandbox.memoryIds.isEmpty();
    }

    private void trimTagPool() {
        trimLinkedMapByScore(tagPool, TAG_POOL_CAP, TAG_POOL_EPSILON, entry -> entry.score);
    }

    private void trimFermentationBuffer() {
        trimLinkedMapByScore(fermentationBuffer, FERMENTATION_BUFFER_CAP, FERMENTATION_EPSILON, entry -> entry.score);
    }

    private static List<String> toFocusTagNames(Collection<FocusTag> focusTags) {
        List<String> result = new ArrayList<>();
        if (focusTags == null) {
            return result;
        }
        for (FocusTag tag : focusTags) {
            if (tag != null) {
                result.add(tag.name());
            }
        }
        return result;
    }

    private static List<String> toDetailTagNames(Collection<DetailTag> detailTags) {
        List<String> result = new ArrayList<>();
        if (detailTags == null) {
            return result;
        }
        for (DetailTag tag : detailTags) {
            if (tag != null) {
                result.add(tag.toString());
            }
        }
        return result;
    }

    private static TagPoolEntry copyTagPoolEntry(TagPoolEntry source) {
        TagPoolEntry copy = new TagPoolEntry();
        copy.tag = source.tag;
        copy.score = source.score;
        copy.sourceCount = source.sourceCount;
        copy.focusTags.addAll(source.focusTags);
        copy.lastInjectedGameTime = source.lastInjectedGameTime;
        copy.lastMatchedGameTime = source.lastMatchedGameTime;
        copy.newlyInjectedThisTurn = source.newlyInjectedThisTurn;
        copy.injectedScoreThisTurn = source.injectedScoreThisTurn;
        return copy;
    }

    private static FermentationMemoryEntry copyFermentationEntry(FermentationMemoryEntry source) {
        FermentationMemoryEntry copy = new FermentationMemoryEntry();
        copy.memoryId = source.memoryId;
        copy.description = source.description;
        copy.score = source.score;
        copy.sourceType = source.sourceType;
        copy.focusTags.addAll(source.focusTags);
        copy.supportTags.addAll(source.supportTags);
        copy.associativeTags.addAll(source.associativeTags);
        copy.lastUpdatedGameTime = source.lastUpdatedGameTime;
        copy.lastConsciousGameTime = source.lastConsciousGameTime;
        copy.enteredConsciousThisTurn = source.enteredConsciousThisTurn;
        copy.cueBoostThisTurn = source.cueBoostThisTurn;
        return copy;
    }

    private static ConsciousMemoryEntry copyConsciousEntry(ConsciousMemoryEntry source) {
        ConsciousMemoryEntry copy = new ConsciousMemoryEntry();
        copy.memoryId = source.memoryId;
        copy.description = source.description;
        copy.score = source.score;
        copy.sourceType = source.sourceType;
        copy.focusTags.addAll(source.focusTags);
        copy.supportTags.addAll(source.supportTags);
        copy.associativeTags.addAll(source.associativeTags);
        return copy;
    }

    private static AssociationSandbox copyAssociationSandbox(AssociationSandbox source) {
        AssociationSandbox copy = new AssociationSandbox();
        copy.active = source.active;
        copy.cueTags.addAll(source.cueTags);
        copy.sourceMemoryIds.addAll(source.sourceMemoryIds);
        copy.memoryIds.addAll(source.memoryIds);
        copy.hopLimit = source.hopLimit;
        copy.recallBudget = source.recallBudget;
        return copy;
    }

    private static <T> void mergeUnique(List<T> target, Collection<T> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (T item : source) {
            if (item != null && !target.contains(item)) {
                target.add(item);
            }
        }
    }

    private static void appendUniqueCapped(List<String> target, String item, int cap) {
        if (item == null || item.isBlank() || target.contains(item) || target.size() >= cap) {
            return;
        }
        target.add(item);
    }

    private static <K, V> void trimLinkedMapByScore(Map<K, V> map, int cap, double epsilon,
            java.util.function.ToDoubleFunction<V> scoreFunction) {
        if (map.isEmpty()) {
            return;
        }

        List<Map.Entry<K, V>> orderedEntries = new ArrayList<>(map.entrySet());
        orderedEntries.sort(Comparator.comparingDouble((Map.Entry<K, V> entry) -> scoreFunction.applyAsDouble(entry.getValue()))
                .reversed());

        map.clear();
        for (Map.Entry<K, V> entry : orderedEntries) {
            if (map.size() >= cap || scoreFunction.applyAsDouble(entry.getValue()) < epsilon) {
                continue;
            }
            map.put(entry.getKey(), entry.getValue());
        }
    }

    private static double intervalBonus(long deltaGameTime) {
        if (deltaGameTime <= 0L) {
            return 1.0;
        }
        double days = deltaGameTime / (double) WorldTimeSemantics.TICKS_PER_DAY;
        double scale = Math.max(0.0, TalkingWeaponConfig.LTM_RECALL_INTERVAL_BONUS_SCALE.get());
        double steepness = Math.max(0.1, TalkingWeaponConfig.LTM_RECALL_INTERVAL_BONUS_STEEPNESS.get());
        double bonus = 1.0 + Math.min(MAX_INTERVAL_BONUS - 1.0, Math.log1p(days * steepness) * scale);
        return clamp(1.0, MAX_INTERVAL_BONUS, bonus);
    }

    private static double decayFactor(long deltaGameTime, long halfLifeTicks) {
        if (deltaGameTime <= 0L || halfLifeTicks <= 0L) {
            return 1.0;
        }
        return Math.exp(-deltaGameTime / (double) halfLifeTicks);
    }

    private static long resolveTagPoolHalfLifeTicks() {
        double days = TalkingWeaponConfig.LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS.get();
        return Math.max(1L, Math.round(WorldTimeSemantics.TICKS_PER_DAY * days));
    }

    private static long resolveFermentationHalfLifeTicks() {
        double days = TalkingWeaponConfig.LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS.get();
        return Math.max(1L, Math.round(WorldTimeSemantics.TICKS_PER_DAY * days));
    }

    private static double clamp(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static final class TagPoolAccumulator {
        final DetailTag tag;
        double score;
        int sourceCount;
        final LinkedHashSet<String> focusTags = new LinkedHashSet<>();

        TagPoolAccumulator(DetailTag tag) {
            this.tag = tag;
        }
    }

    public static final class Snapshot {
        public String captureMode = CAPTURE_MODE_UNIFIED_RUNTIME;
        public long turnCounter;
        public long currentGameTime = -1L;
        public long previousGameTime = -1L;
        public long deltaGameTime;
        public long wallClockTime = -1L;
        public int visibleContextCount;
        public int recalledMemoryCount;
        public List<TagPoolEntry> tagPool = new ArrayList<>();
        public List<FermentationMemoryEntry> fermentationBuffer = new ArrayList<>();
        public List<ConsciousMemoryEntry> consciousMemories = new ArrayList<>();
        public AssociationSandbox associationSandbox = new AssociationSandbox();
    }

    public static class TagPoolEntry {
        public String tag = "";
        public double score;
        public int sourceCount;
        public List<String> focusTags = new ArrayList<>();
        public long lastInjectedGameTime = -1L;
        public long lastMatchedGameTime = -1L;
        public boolean newlyInjectedThisTurn;
        public double injectedScoreThisTurn;
    }

    public static class FermentationMemoryEntry {
        public String memoryId = "";
        public String description = "";
        public double score;
        public String sourceType = "";
        public List<String> focusTags = new ArrayList<>();
        public List<String> supportTags = new ArrayList<>();
        public List<String> associativeTags = new ArrayList<>();
        public long lastUpdatedGameTime = -1L;
        public long lastConsciousGameTime = -1L;
        public boolean enteredConsciousThisTurn;
        public double cueBoostThisTurn;
    }

    public static class ConsciousMemoryEntry {
        public String memoryId = "";
        public String description = "";
        public double score;
        public String sourceType = "";
        public List<String> focusTags = new ArrayList<>();
        public List<String> supportTags = new ArrayList<>();
        public List<String> associativeTags = new ArrayList<>();
    }

    public static class AssociationSandbox {
        public boolean active;
        public List<String> cueTags = new ArrayList<>();
        public List<String> sourceMemoryIds = new ArrayList<>();
        public List<String> memoryIds = new ArrayList<>();
        public int hopLimit = 1;
        public int recallBudget;

        void clear() {
            active = false;
            cueTags.clear();
            sourceMemoryIds.clear();
            memoryIds.clear();
            hopLimit = 1;
            recallBudget = 0;
        }
    }
}
