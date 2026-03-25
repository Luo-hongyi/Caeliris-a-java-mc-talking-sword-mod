package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * prompt / 近期感知层使用的 moving segment buffer。
 * <p>
 * 这层直接从原始动作流增量读取，并维护独立的 moving segment。
 * 它可以做轻量 local intent 分析，但不参与 episode 边界和最终 LTM 生成。
 */
public final class MovingActionBuffer {

    public static final long DEFAULT_TIME_WINDOW_MS = 180_000L;
    public static final long DEFAULT_MAX_SEGMENT_DURATION_MS = 60_000L;
    public static final long DEFAULT_IDLE_SEAL_MS = 12_000L;
    public static final int DEFAULT_MAX_SEALED_SEGMENTS = 8;
    public static final int DEFAULT_BLOCK_FOLD_THRESHOLD = 5;
    public static final int DEFAULT_BLOCK_FOLD_TOP_TARGETS = 5;

    private final int minSealedSegments;
    private long timeWindowMs;
    private final long maxSegmentDurationMs;
    private final long idleSealMs;
    private final int blockFoldThreshold;
    private final int blockFoldTopTargets;
    private final ArrayDeque<MutableSegment> sealedSegments;
    private MutableSegment openSegment;

    public MovingActionBuffer() {
        this(
                DEFAULT_MAX_SEALED_SEGMENTS,
                DEFAULT_TIME_WINDOW_MS,
                DEFAULT_MAX_SEGMENT_DURATION_MS,
                DEFAULT_IDLE_SEAL_MS,
                DEFAULT_BLOCK_FOLD_THRESHOLD,
                DEFAULT_BLOCK_FOLD_TOP_TARGETS);
    }

    public MovingActionBuffer(
            int maxSealedSegments,
            long timeWindowMs,
            long maxSegmentDurationMs,
            long idleSealMs) {
        this(
                maxSealedSegments,
                timeWindowMs,
                maxSegmentDurationMs,
                idleSealMs,
                DEFAULT_BLOCK_FOLD_THRESHOLD,
                DEFAULT_BLOCK_FOLD_TOP_TARGETS);
    }

    public MovingActionBuffer(
            int maxSealedSegments,
            long timeWindowMs,
            long maxSegmentDurationMs,
            long idleSealMs,
            int blockFoldThreshold,
            int blockFoldTopTargets) {
        this.minSealedSegments = Math.max(1, maxSealedSegments);
        this.timeWindowMs = Math.max(1L, timeWindowMs);
        this.maxSegmentDurationMs = Math.max(1L, maxSegmentDurationMs);
        this.idleSealMs = Math.max(1L, idleSealMs);
        this.blockFoldThreshold = Math.max(1, blockFoldThreshold);
        this.blockFoldTopTargets = Math.max(1, blockFoldTopTargets);
        this.sealedSegments = new ArrayDeque<>(this.minSealedSegments);
    }

    public synchronized void add(ActionRecord record) {
        if (record == null) {
            return;
        }

        syncRuntimeSettings();
        long timestamp = Math.max(0L, record.getTimestamp());
        long now = Math.max(System.currentTimeMillis(), timestamp);
        sealOpenIfNeeded(timestamp);
        ensureOpenSegment(timestamp);
        openSegment.merge(record);
        evictStale(now);
    }

    public synchronized void flushOpenSegment() {
        sealOpenSegment(System.currentTimeMillis());
    }

    public synchronized List<MovingActionSegment> snapshotSegments() {
        syncRuntimeSettings();
        MovingActionSegment promptWindow = snapshotPromptWindowSegment();
        if (promptWindow == null || promptWindow.getEntries().isEmpty()) {
            return List.of();
        }
        return List.of(promptWindow);
    }

    public synchronized List<MovingActionSegment> snapshotRawSegments() {
        syncRuntimeSettings();
        return snapshotRawSegmentsInternal();
    }

    public synchronized MovingActionSegment snapshotCurrentSegment() {
        syncRuntimeSettings();
        MovingActionSegment raw = snapshotCurrentRawSegment();
        if (raw == null) {
            return null;
        }
        return foldSegment(raw);
    }

    public synchronized MovingActionSegment snapshotCurrentRawSegment() {
        syncRuntimeSettings();
        return snapshotPromptWindowRawSegment();
    }

    public synchronized MovingActionSegment snapshotPromptWindowSegment() {
        syncRuntimeSettings();
        MovingActionSegment raw = snapshotPromptWindowRawSegment();
        if (raw == null) {
            return null;
        }
        return foldSegment(raw);
    }

    public synchronized MovingActionSegment snapshotPromptWindowRawSegment() {
        syncRuntimeSettings();
        long now = System.currentTimeMillis();
        sealIdleOpenSegment(now);
        evictStale(now);
        return mergeRawSegmentsToWindow(snapshotRawSegmentsInternal());
    }

    public synchronized List<SegmentIntentSignals> snapshotIntentSignals() {
        syncRuntimeSettings();
        MovingActionSegment rawWindow = snapshotPromptWindowRawSegment();
        if (rawWindow == null || rawWindow.getEntries().isEmpty()) {
            return List.of();
        }
        return List.of(PlayerIntentScorer.analyze(rawWindow));
    }

    private List<MovingActionSegment> snapshotRawSegmentsInternal() {
        long now = System.currentTimeMillis();
        sealIdleOpenSegment(now);
        evictStale(now);

        List<MovingActionSegment> segments = new ArrayList<>(sealedSegments.size() + (openSegment == null ? 0 : 1));
        for (MutableSegment sealed : sealedSegments) {
            segments.add(sealed.freeze(false));
        }
        if (openSegment != null && !openSegment.isEmpty()) {
            segments.add(openSegment.freeze(true));
        }
        return List.copyOf(segments);
    }

    private MovingActionSegment mergeRawSegmentsToWindow(List<MovingActionSegment> rawSegments) {
        if (rawSegments == null || rawSegments.isEmpty()) {
            return null;
        }

        long startTimestamp = Long.MAX_VALUE;
        long endTimestamp = Long.MIN_VALUE;
        boolean open = false;
        LinkedHashMap<EntryKey, WindowMutableEntry> aggregated = new LinkedHashMap<>();

        for (MovingActionSegment segment : rawSegments) {
            if (segment == null || segment.getEntries().isEmpty()) {
                continue;
            }
            startTimestamp = Math.min(startTimestamp, segment.getStartTimestamp());
            endTimestamp = Math.max(endTimestamp, segment.getEndTimestamp());
            open = open || segment.isOpen();

            for (MovingActionSegmentEntry entry : segment.getEntries()) {
                if (entry == null || entry.getType() == null) {
                    continue;
                }
                removeCoveredEntries(aggregated, entry.getType(), normalizeTarget(entry.getTarget()));
                EntryKey key = new EntryKey(entry.getType(), normalizeTarget(entry.getTarget()));
                WindowMutableEntry existing = aggregated.remove(key);
                if (existing == null) {
                    aggregated.put(key, new WindowMutableEntry(entry));
                } else {
                    existing.merge(entry);
                    aggregated.put(key, existing);
                }
            }
        }

        if (aggregated.isEmpty()) {
            return null;
        }

        List<MovingActionSegmentEntry> entries = new ArrayList<>(aggregated.size());
        for (WindowMutableEntry entry : aggregated.values()) {
            entries.add(entry.freeze());
        }
        entries.sort(Comparator.comparingLong(MovingActionSegmentEntry::getFirstTimestamp));

        return new MovingActionSegment(
                startTimestamp == Long.MAX_VALUE ? 0L : startTimestamp,
                endTimestamp == Long.MIN_VALUE ? 0L : endTimestamp,
                open,
                entries);
    }

    private void removeCoveredEntries(
            LinkedHashMap<EntryKey, WindowMutableEntry> aggregated,
            ActionType type,
            String target) {
        if (aggregated == null || type == null || target == null || target.isEmpty()) {
            return;
        }
        if (type == ActionType.KILL) {
            aggregated.remove(new EntryKey(ActionType.ATTACK, target));
        }
        if (type == ActionType.USE_GATHER_TOOL) {
            aggregated.remove(new EntryKey(ActionType.MINE_BLOCK, target));
        }
    }

    private MovingActionSegment foldSegment(MovingActionSegment segment) {
        if (segment == null || segment.getEntries().isEmpty()) {
            return segment;
        }

        List<MovingActionSegmentEntry> keptEntries = new ArrayList<>();
        foldBlockEntriesOfType(segment, ActionType.MINE_BLOCK, keptEntries);
        foldBlockEntriesOfType(segment, ActionType.PLACE_BLOCK, keptEntries);

        for (MovingActionSegmentEntry entry : segment.getEntries()) {
            if (entry.getType() == ActionType.MINE_BLOCK || entry.getType() == ActionType.PLACE_BLOCK) {
                continue;
            }
            if (isHiddenInPromptView(entry.getType())) {
                continue;
            }
            keptEntries.add(entry);
        }

        keptEntries.sort(Comparator.comparingLong(MovingActionSegmentEntry::getFirstTimestamp));
        return new MovingActionSegment(
                segment.getStartTimestamp(),
                segment.getEndTimestamp(),
                segment.isOpen(),
                keptEntries);
    }

    private static boolean isHiddenInPromptView(ActionType type) {
        return type == ActionType.ACQUIRE_ITEM;
    }

    private void foldBlockEntriesOfType(
            MovingActionSegment segment,
            ActionType type,
            List<MovingActionSegmentEntry> output) {
        List<MovingActionSegmentEntry> blockEntries = new ArrayList<>();
        for (MovingActionSegmentEntry entry : segment.getEntries()) {
            if (entry.getType() == type) {
                blockEntries.add(entry);
            }
        }
        if (blockEntries.isEmpty()) {
            return;
        }
        if (blockEntries.size() <= blockFoldThreshold) {
            output.addAll(blockEntries);
            return;
        }

        // prompt 侧如果短时间内出现大量不同 block target，
        // 不再合成一个 "various_blocks" 占位条目，
        // 而是只保留数量排名前 N 的具体 block target。
        List<MovingActionSegmentEntry> rankedEntries = blockEntries.stream()
                .sorted(Comparator
                        .comparingInt(MovingActionSegmentEntry::getCount)
                        .reversed()
                        .thenComparing(entry -> entry.getTarget() == null ? "" : entry.getTarget()))
                .toList();
        output.addAll(rankedEntries.stream()
                .limit(blockFoldTopTargets)
                .toList());
    }

    public synchronized boolean isEmpty() {
        syncRuntimeSettings();
        long now = System.currentTimeMillis();
        sealIdleOpenSegment(now);
        evictStale(now);
        return sealedSegments.isEmpty() && (openSegment == null || openSegment.isEmpty());
    }

    public synchronized int segmentCount() {
        syncRuntimeSettings();
        long now = System.currentTimeMillis();
        sealIdleOpenSegment(now);
        evictStale(now);
        return sealedSegments.size() + (openSegment == null || openSegment.isEmpty() ? 0 : 1);
    }

    public synchronized void clear() {
        sealedSegments.clear();
        openSegment = null;
    }

    public synchronized long getTimeWindowMs() {
        syncRuntimeSettings();
        return timeWindowMs;
    }

    private void ensureOpenSegment(long timestamp) {
        if (openSegment == null || openSegment.isEmpty()) {
            openSegment = new MutableSegment(timestamp);
        }
    }

    private void sealOpenIfNeeded(long incomingTimestamp) {
        if (openSegment == null || openSegment.isEmpty()) {
            return;
        }
        long lastTimestamp = openSegment.getLastTimestamp();
        long startTimestamp = openSegment.getStartTimestamp();
        boolean idleExceeded = incomingTimestamp - lastTimestamp > idleSealMs;
        boolean durationExceeded = incomingTimestamp - startTimestamp > maxSegmentDurationMs;
        if (idleExceeded || durationExceeded) {
            sealOpenSegment(lastTimestamp);
        }
    }

    private void sealIdleOpenSegment(long now) {
        if (openSegment == null || openSegment.isEmpty()) {
            return;
        }
        if (now - openSegment.getLastTimestamp() > idleSealMs) {
            sealOpenSegment(openSegment.getLastTimestamp());
        }
    }

    private void sealOpenSegment(long timestamp) {
        if (openSegment == null || openSegment.isEmpty()) {
            openSegment = null;
            return;
        }
        openSegment.finish(timestamp);
        sealedSegments.addLast(openSegment);
        while (sealedSegments.size() > computeSealedSegmentCapacity()) {
            sealedSegments.pollFirst();
        }
        openSegment = null;
    }

    private void evictStale(long now) {
        while (!sealedSegments.isEmpty()) {
            MutableSegment oldest = sealedSegments.peekFirst();
            if (oldest == null || now - oldest.getLastTimestamp() <= timeWindowMs) {
                break;
            }
            sealedSegments.pollFirst();
        }
        if (openSegment != null && !openSegment.isEmpty() && now - openSegment.getLastTimestamp() > timeWindowMs) {
            openSegment = null;
        }
    }

    private void syncRuntimeSettings() {
        this.timeWindowMs = Math.max(1L, ActionTimelineRuntimeSettings.getMovingWindowMs());
    }

    private int computeSealedSegmentCapacity() {
        long required = (long) Math.ceil((double) Math.max(1L, timeWindowMs) / (double) Math.max(1L, maxSegmentDurationMs)) + 2L;
        return Math.max(minSealedSegments, (int) Math.min(Integer.MAX_VALUE, required));
    }

    private record EntryKey(ActionType type, String target) {
    }

    private static final class MutableSegment {
        private final long startTimestamp;
        private long lastTimestamp;
        private final LinkedHashMap<EntryKey, MutableEntry> entries = new LinkedHashMap<>();

        private MutableSegment(long startTimestamp) {
            this.startTimestamp = Math.max(0L, startTimestamp);
            this.lastTimestamp = this.startTimestamp;
        }

        private long getStartTimestamp() {
            return startTimestamp;
        }

        private long getLastTimestamp() {
            return lastTimestamp;
        }

        private boolean isEmpty() {
            return entries.isEmpty();
        }

        private void finish(long timestamp) {
            this.lastTimestamp = Math.max(this.lastTimestamp, Math.max(0L, timestamp));
        }

        private void merge(ActionRecord record) {
            if (record == null) {
                return;
            }
            long timestamp = Math.max(0L, record.getTimestamp());
            this.lastTimestamp = Math.max(this.lastTimestamp, timestamp);

            removeCoveredEntries(record);

            EntryKey key = new EntryKey(record.getType(), normalizeTarget(record.getTarget()));
            MutableEntry existing = entries.remove(key);
            if (existing == null) {
                entries.put(key, new MutableEntry(record));
            } else {
                existing.merge(record);
                entries.put(key, existing);
            }
        }

        private void removeCoveredEntries(ActionRecord record) {
            String target = normalizeTarget(record.getTarget());
            if (target.isEmpty()) {
                return;
            }
            if (record.getType() == ActionType.KILL) {
                entries.remove(new EntryKey(ActionType.ATTACK, target));
            }
            if (record.getType() == ActionType.USE_GATHER_TOOL) {
                entries.remove(new EntryKey(ActionType.MINE_BLOCK, target));
            }
        }

        private MovingActionSegment freeze(boolean open) {
            List<MovingActionSegmentEntry> snapshotEntries = new ArrayList<>(entries.size());
            for (MutableEntry entry : entries.values()) {
                snapshotEntries.add(entry.freeze());
            }
            long endTimestamp = open ? lastTimestamp : Math.max(lastTimestamp, startTimestamp);
            return new MovingActionSegment(startTimestamp, endTimestamp, open, snapshotEntries);
        }
    }

    private static final class MutableEntry {
        private final ActionType type;
        private final String target;
        private int count;
        private long firstTimestamp;
        private long lastTimestamp;
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();

        private MutableEntry(ActionRecord record) {
            this.type = record.getType();
            this.target = normalizeTarget(record.getTarget());
            this.count = Math.max(1, record.getCount());
            this.firstTimestamp = Math.max(0L, record.getTimestamp());
            this.lastTimestamp = this.firstTimestamp;
            this.detailTags.addAll(record.getDetailTags());
        }

        private void merge(ActionRecord record) {
            if (record == null || record.getType() != type || !Objects.equals(normalizeTarget(record.getTarget()), target)) {
                return;
            }
            this.count += Math.max(1, record.getCount());
            long timestamp = Math.max(0L, record.getTimestamp());
            this.firstTimestamp = Math.min(firstTimestamp, timestamp);
            this.lastTimestamp = Math.max(lastTimestamp, timestamp);
            this.detailTags.addAll(record.getDetailTags());
        }

        private MovingActionSegmentEntry freeze() {
            return new MovingActionSegmentEntry(
                    type,
                    target,
                    count,
                    firstTimestamp,
                    lastTimestamp,
                    detailTags);
        }
    }

    private static final class WindowMutableEntry {
        private final ActionType type;
        private final String target;
        private int count;
        private long firstTimestamp;
        private long lastTimestamp;
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();

        private WindowMutableEntry(MovingActionSegmentEntry entry) {
            this.type = entry.getType();
            this.target = normalizeTarget(entry.getTarget());
            this.count = Math.max(1, entry.getCount());
            this.firstTimestamp = Math.max(0L, entry.getFirstTimestamp());
            this.lastTimestamp = Math.max(0L, entry.getLastTimestamp());
            this.detailTags.addAll(entry.getDetailTags());
        }

        private void merge(MovingActionSegmentEntry entry) {
            if (entry == null || entry.getType() != type || !Objects.equals(normalizeTarget(entry.getTarget()), target)) {
                return;
            }
            this.count += Math.max(1, entry.getCount());
            this.firstTimestamp = Math.min(firstTimestamp, Math.max(0L, entry.getFirstTimestamp()));
            this.lastTimestamp = Math.max(lastTimestamp, Math.max(0L, entry.getLastTimestamp()));
            this.detailTags.addAll(entry.getDetailTags());
        }

        private MovingActionSegmentEntry freeze() {
            return new MovingActionSegmentEntry(
                    type,
                    target,
                    count,
                    firstTimestamp,
                    lastTimestamp,
                    detailTags);
        }
    }

    private static String normalizeTarget(String target) {
        return target == null ? "" : target;
    }
}
