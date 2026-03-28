package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FixedActionSegment 的正式缓冲区。
 * <p>
 * 这里明确区分：
 * <ul>
 * <li>sealed segments: 已经正式产出，可发送给 episode / 持久化</li>
 * <li>open segment: 当前仍在累加中的窗口，不应直接作为正式 fixed segment 消费</li>
 * </ul>
 */
public final class FixedActionSegmentBuffer {

    private final List<FixedActionSegment> sealedSegments = new ArrayList<>();
    private final List<ActionArchiveCompactor.FixedSegmentContextSeed> contextSeeds = new ArrayList<>();
    private MutableOpenSegment openSegment;
    private long fixedSegmentMs = -1L;
    private long nextSegmentId = 1L;
    private boolean dirty = true;

    public void invalidate() {
        dirty = true;
    }

    public void clear() {
        sealedSegments.clear();
        contextSeeds.clear();
        openSegment = null;
        fixedSegmentMs = -1L;
        nextSegmentId = 1L;
        dirty = true;
    }

    public boolean needsRebuild(long segmentMs) {
        long effectiveSegmentMs = Math.max(1L, segmentMs);
        return dirty || fixedSegmentMs != effectiveSegmentMs;
    }

    public void rebuild(List<ActionRecord> records, long segmentMs, long now) {
        long effectiveSegmentMs = Math.max(1L, segmentMs);
        FixedSegmentBufferRuntimeState previousState = snapshotRuntimeState();
        sealedSegments.clear();
        openSegment = null;

        List<FixedActionSegment> rebuilt = ActionArchiveCompactor.compact(records, effectiveSegmentMs, contextSeeds);
        if (!rebuilt.isEmpty()) {
            FixedActionSegment last = rebuilt.get(rebuilt.size() - 1);
            if (now < last.getEndTimestamp()) {
                if (rebuilt.size() > 1) {
                    sealedSegments.addAll(assignSegmentIds(rebuilt.subList(0, rebuilt.size() - 1), previousState));
                }
                openSegment = MutableOpenSegment.from(last);
            } else {
                sealedSegments.addAll(assignSegmentIds(rebuilt, previousState));
            }
        }

        fixedSegmentMs = effectiveSegmentMs;
        pruneContextSeeds(records);
        dirty = false;
    }

    public FixedSegmentUpdate append(ActionRecord record, int rawIndex, long segmentMs, long now) {
        if (record == null) {
            return FixedSegmentUpdate.none();
        }

        long effectiveSegmentMs = Math.max(1L, segmentMs);
        if (needsRebuild(effectiveSegmentMs)) {
            dirty = true;
            return FixedSegmentUpdate.none();
        }

        List<FixedActionSegment> newlySealed = new ArrayList<>();
        FixedActionSegment elapsedSeal = sealElapsedOpenSegment(now);
        if (elapsedSeal != null) {
            newlySealed.add(elapsedSeal);
        }

        long timestamp = Math.max(0L, record.getTimestamp());
        long windowStartTimestamp = windowStart(timestamp, effectiveSegmentMs);
        long windowEndTimestamp = windowStartTimestamp + effectiveSegmentMs;

        if (openSegment == null) {
            openSegment = new MutableOpenSegment(
                    windowStartTimestamp,
                    windowEndTimestamp,
                    rawIndex,
                    record,
                    defaultContextFor(record));
            return FixedSegmentUpdate.append(newlySealed);
        }

        if (openSegment.startTimestamp == windowStartTimestamp) {
            openSegment.merge(record, rawIndex);
            return FixedSegmentUpdate.append(newlySealed);
        }

        if (windowStartTimestamp > openSegment.startTimestamp) {
            newlySealed.add(sealOpenSegment());
            openSegment = new MutableOpenSegment(
                    windowStartTimestamp,
                    windowEndTimestamp,
                    rawIndex,
                    record,
                    defaultContextFor(record));
            return FixedSegmentUpdate.append(newlySealed);
        }

        dirty = true;
        return FixedSegmentUpdate.none();
    }

    public FixedSegmentUpdate sealElapsedSegments(long now) {
        FixedActionSegment sealed = sealElapsedOpenSegment(now);
        return sealed == null ? FixedSegmentUpdate.none() : FixedSegmentUpdate.append(sealed);
    }

    public List<FixedActionSegment> snapshotSealedSegments() {
        return List.copyOf(sealedSegments);
    }

    public FixedActionSegment snapshotOpenSegment() {
        return openSegment == null ? null : openSegment.freeze();
    }

    public FixedSegmentBufferRuntimeState snapshotRuntimeState() {
        if (fixedSegmentMs <= 0L && sealedSegments.isEmpty() && openSegment == null && nextSegmentId <= 1L) {
            return FixedSegmentBufferRuntimeState.empty();
        }
        return new FixedSegmentBufferRuntimeState(
                fixedSegmentMs,
                nextSegmentId,
                snapshotSealedSegments(),
                snapshotOpenSegment());
    }

    public void restoreRuntimeState(FixedSegmentBufferRuntimeState runtimeState) {
        clear();
        if (runtimeState == null || runtimeState.isEmpty()) {
            return;
        }
        fixedSegmentMs = Math.max(1L, runtimeState.getFixedSegmentMs());
        nextSegmentId = Math.max(1L, runtimeState.getNextSegmentId());
        if (runtimeState.getSealedSegments() != null && !runtimeState.getSealedSegments().isEmpty()) {
            sealedSegments.addAll(runtimeState.getSealedSegments());
            long maxObservedSegmentId = sealedSegments.stream()
                    .mapToLong(FixedActionSegment::getSegmentId)
                    .max()
                    .orElse(FixedActionSegment.UNASSIGNED_SEGMENT_ID);
            nextSegmentId = Math.max(nextSegmentId, maxObservedSegmentId + 1L);
        }
        FixedActionSegment persistedOpenSegment = runtimeState.getOpenSegment();
        if (persistedOpenSegment != null) {
            openSegment = MutableOpenSegment.from(persistedOpenSegment);
        }
        dirty = false;
    }

    private FixedActionSegment sealElapsedOpenSegment(long now) {
        if (openSegment == null) {
            return null;
        }
        if (Math.max(0L, now) < openSegment.endTimestamp) {
            return null;
        }
        return sealOpenSegment();
    }

    private FixedActionSegment sealOpenSegment() {
        if (openSegment == null) {
            return null;
        }
        FixedActionSegment sealed = openSegment.freeze(allocateSegmentId());
        sealedSegments.add(sealed);
        openSegment = null;
        return sealed;
    }

    private long allocateSegmentId() {
        return nextSegmentId++;
    }

    private List<FixedActionSegment> assignSegmentIds(
            List<FixedActionSegment> rebuiltSegments,
            FixedSegmentBufferRuntimeState previousState) {
        if (rebuiltSegments == null || rebuiltSegments.isEmpty()) {
            return List.of();
        }

        Map<SegmentWindowKey, Long> existingIds = new LinkedHashMap<>();
        if (previousState != null && previousState.getSealedSegments() != null) {
            for (FixedActionSegment segment : previousState.getSealedSegments()) {
                if (segment == null || !segment.hasAssignedSegmentId()) {
                    continue;
                }
                existingIds.put(SegmentWindowKey.of(segment), segment.getSegmentId());
            }
        }

        ArrayList<FixedActionSegment> assigned = new ArrayList<>(rebuiltSegments.size());
        for (FixedActionSegment segment : rebuiltSegments) {
            if (segment == null) {
                continue;
            }
            Long existingId = existingIds.get(SegmentWindowKey.of(segment));
            if (existingId != null && existingId > 0L) {
                assigned.add(segment.withSegmentId(existingId));
                continue;
            }
            assigned.add(segment.withSegmentId(allocateSegmentId()));
        }
        return List.copyOf(assigned);
    }

    private static long windowStart(long timestamp, long fixedSegmentMs) {
        return Math.max(0L, timestamp) / fixedSegmentMs * fixedSegmentMs;
    }

    private FixedActionSegmentContext defaultContextFor(ActionRecord record) {
        long timestamp = record == null ? 0L : Math.max(0L, record.getTimestamp());
        for (ActionArchiveCompactor.FixedSegmentContextSeed seed : contextSeeds) {
            if (seed != null && seed.capturedAtTimestamp() == timestamp) {
                return seed.context();
            }
        }

        FixedActionSegmentContext context = FixedSegmentDefaultContextCollector.captureCurrentDefaultContext();
        contextSeeds.add(new ActionArchiveCompactor.FixedSegmentContextSeed(timestamp, context));
        contextSeeds.sort(Comparator.comparingLong(ActionArchiveCompactor.FixedSegmentContextSeed::capturedAtTimestamp));
        return context;
    }

    private void pruneContextSeeds(List<ActionRecord> records) {
        if (contextSeeds.isEmpty()) {
            return;
        }
        if (records == null || records.isEmpty()) {
            contextSeeds.clear();
            return;
        }
        long minTimestamp = Long.MAX_VALUE;
        for (ActionRecord record : records) {
            if (record == null) {
                continue;
            }
            minTimestamp = Math.min(minTimestamp, Math.max(0L, record.getTimestamp()));
        }
        if (minTimestamp == Long.MAX_VALUE) {
            contextSeeds.clear();
            return;
        }
        long finalMinTimestamp = minTimestamp;
        contextSeeds.removeIf(seed -> seed == null || seed.capturedAtTimestamp() < finalMinTimestamp);
    }

    private static final class MutableOpenSegment {
        private final long startTimestamp;
        private final long endTimestamp;
        private final int rawStartIndex;
        private int rawEndIndex;
        private final List<FixedActionSegmentEntry> entries = new ArrayList<>();
        private final FixedActionSegmentContext context;

        private MutableOpenSegment(
                long startTimestamp,
                long endTimestamp,
                int rawStartIndex,
                int rawEndIndex,
                FixedActionSegmentContext context) {
            this.startTimestamp = startTimestamp;
            this.endTimestamp = endTimestamp;
            this.rawStartIndex = rawStartIndex;
            this.rawEndIndex = rawEndIndex;
            this.context = context == null ? FixedActionSegmentContext.empty() : context;
        }

        private MutableOpenSegment(
                long startTimestamp,
                long endTimestamp,
                int rawIndex,
                ActionRecord record,
                FixedActionSegmentContext context) {
            this(startTimestamp, endTimestamp, rawIndex, rawIndex, context);
            addOrMerge(record, rawIndex);
        }

        private static MutableOpenSegment from(FixedActionSegment segment) {
            MutableOpenSegment open = new MutableOpenSegment(
                    segment.getStartTimestamp(),
                    segment.getEndTimestamp(),
                    segment.getRawStartIndex(),
                    segment.getRawEndIndex(),
                    segment.getContext());
            open.entries.addAll(segment.getEntries());
            return open;
        }

        private void merge(ActionRecord record, int rawIndex) {
            addOrMerge(record, rawIndex);
            rawEndIndex = rawIndex;
        }

        private void addOrMerge(ActionRecord record, int rawIndex) {
            if (record == null) {
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                FixedActionSegmentEntry entry = entries.get(i);
                if (entry.getType() != record.getType()
                        || !Objects.equals(entry.getTarget(), record.getTarget())) {
                    continue;
                }
                entries.set(i, mergeEntry(entry, record, rawIndex));
                return;
            }
            entries.add(new FixedActionSegmentEntry(
                    record.getType(),
                    record.getTarget(),
                    Math.max(1, record.getCount()),
                    Math.max(0L, record.getTimestamp()),
                    Math.max(0L, record.getTimestamp()),
                    rawIndex,
                    rawIndex,
                    record.getDetailTags()));
        }

        private FixedActionSegment freeze() {
            return freeze(FixedActionSegment.UNASSIGNED_SEGMENT_ID);
        }

        private FixedActionSegment freeze(long segmentId) {
            return new FixedActionSegment(
                    segmentId,
                    startTimestamp,
                    endTimestamp,
                    rawStartIndex,
                    rawEndIndex,
                    entries,
                    null,
                    context);
        }

        private FixedActionSegmentEntry mergeEntry(FixedActionSegmentEntry entry, ActionRecord record, int rawIndex) {
            Set<DetailTag> mergedTags = new LinkedHashSet<>(entry.getDetailTags());
            mergedTags.addAll(record.getDetailTags());
            long timestamp = Math.max(0L, record.getTimestamp());
            return new FixedActionSegmentEntry(
                    entry.getType(),
                    entry.getTarget(),
                    entry.getCount() + Math.max(1, record.getCount()),
                    Math.min(entry.getFirstTimestamp(), timestamp),
                    Math.max(entry.getLastTimestamp(), timestamp),
                    entry.getRawStartIndex(),
                    rawIndex,
                    mergedTags);
        }
    }

    private record SegmentWindowKey(
            long startTimestamp,
            long endTimestamp) {

        private static SegmentWindowKey of(FixedActionSegment segment) {
            return new SegmentWindowKey(
                    segment == null ? 0L : segment.getStartTimestamp(),
                    segment == null ? 0L : segment.getEndTimestamp());
        }
    }
}
