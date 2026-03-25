package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.CollectedContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeIntentMath;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Episode v2 的统一 store。
 * <p>
 * 当前第一版先承担：
 * <ul>
 * <li>持有最近一次 timeline 快照</li>
 * <li>作为 tracker 的唯一外部入口</li>
 * <li>未来只对外输出 episode synthesis trigger signal</li>
 * </ul>
 * <p>
 * 后续再在这里挂持久化与 workspace 对接。
 */
public final class EpisodeTimelineStore {

    private EpisodeTimeline timeline = EpisodeTimeline.empty();
    private final EpisodeTracker tracker = new EpisodeTracker();

    public synchronized EpisodeTimeline snapshot() {
        return timeline;
    }

    /**
     * episode 对外的唯一正式输出接口。
     * <p>
     * 未来由 episode 内部负责判断：
     * <ul>
     * <li>是否已有足够多的 confirmed split / closed region</li>
     * <li>最早 closed region 是否值得生成记忆</li>
     * <li>是否应该直接跳过</li>
     * </ul>
     * 当前先留空骨架，明确边界。
     */
    public synchronized Optional<EpisodeSynthesisTriggerSignal> peekEpisodeSynthesisTriggerSignal() {
        return Optional.empty();
    }

    /**
     * 手动调试入口：读取最近一个已经闭合的 region。
     * <p>
     * 这里不承载正式自动调度策略，只解决“右键手动测试 synthesis prompt”时的资格判断。
     */
    public synchronized Optional<EpisodeSynthesisTriggerSignal> peekLatestClosedRegionDebugSignal() {
        List<BoundaryAnchor> boundaries = collectUniqueBoundaries();
        if (boundaries.size() < 2) {
            return Optional.empty();
        }
        return buildClosedRegionSignal(
                boundaries.get(boundaries.size() - 2),
                boundaries.get(boundaries.size() - 1));
    }

    public synchronized Optional<EpisodeSynthesisMaterial> buildSynthesisMaterial(
            EpisodeSynthesisTriggerSignal signal,
            STMPool.TimeSlice stmSlice) {
        if (signal == null || timeline.segments().isEmpty()) {
            return Optional.empty();
        }

        List<FixedActionSegment> baseSegments = timeline.sliceSegments(
                signal.regionStartSegmentIndex(),
                signal.regionEndSegmentIndex());
        if (baseSegments.isEmpty()) {
            return Optional.empty();
        }

        EpisodeRegion region = EpisodeIntentMath.buildRegion(
                timeline.segments(),
                signal.regionStartSegmentIndex(),
                signal.regionEndSegmentIndex());
        if (region == null) {
            return Optional.empty();
        }

        List<FixedActionSegment> fusedSegments = fuseSegments(signal, baseSegments, stmSlice);
        return Optional.of(new EpisodeSynthesisMaterial(signal, region, fusedSegments));
    }

    public synchronized EpisodeTimeline replaceFixedSegments(List<FixedActionSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            tracker.reset();
            timeline = EpisodeTimeline.empty();
            return timeline;
        }
        timeline = tracker.replaceSegments(segments);
        return timeline;
    }

    public synchronized EpisodeTimeline appendFixedSegment(FixedActionSegment segment) {
        if (segment == null) {
            return timeline;
        }
        return appendFixedSegments(List.of(segment));
    }

    public synchronized EpisodeTimeline appendFixedSegments(List<FixedActionSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return timeline;
        }

        ArrayList<FixedActionSegment> merged = new ArrayList<>(timeline.segments());
        ArrayList<FixedActionSegment> appended = new ArrayList<>();
        for (FixedActionSegment segment : segments) {
            if (segment == null) {
                continue;
            }
            if (!merged.isEmpty() && isSameSegment(merged.get(merged.size() - 1), segment)) {
                continue;
            }
            merged.add(segment);
            appended.add(segment);
        }
        if (appended.isEmpty()) {
            return timeline;
        }

        timeline = timeline.segments().isEmpty()
                ? tracker.replaceSegments(merged)
                : tracker.appendSegments(appended);
        return timeline;
    }

    public synchronized void clear() {
        tracker.reset();
        timeline = EpisodeTimeline.empty();
    }

    private boolean isSameSegment(FixedActionSegment left, FixedActionSegment right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getStartTimestamp() == right.getStartTimestamp()
                && left.getEndTimestamp() == right.getEndTimestamp()
                && left.getRawStartIndex() == right.getRawStartIndex()
                && left.getRawEndIndex() == right.getRawEndIndex();
    }

    private List<FixedActionSegment> fuseSegments(
            EpisodeSynthesisTriggerSignal signal,
            List<FixedActionSegment> baseSegments,
            STMPool.TimeSlice stmSlice) {
        ArrayList<List<TaggedContextEntry>> contextBuckets = new ArrayList<>(baseSegments.size());
        ArrayList<List<InteractionTurn>> turnBuckets = new ArrayList<>(baseSegments.size());
        for (int i = 0; i < baseSegments.size(); i++) {
            contextBuckets.add(new ArrayList<>());
            turnBuckets.add(new ArrayList<>());
        }

        if (stmSlice != null && !baseSegments.isEmpty()) {
            long rangeStart = Math.max(signal.regionStartTimestamp(), baseSegments.get(0).getStartTimestamp());
            long rangeEnd = signal.stmSliceExclusiveTimestamp();

            for (TaggedContextEntry entry : stmSlice.getContextEntries()) {
                if (entry == null || !entry.isMemoryEligible()) {
                    continue;
                }
                int bucketIndex = findSegmentBucket(baseSegments, entry.getTimestamp(), rangeStart, rangeEnd);
                if (bucketIndex >= 0) {
                    contextBuckets.get(bucketIndex).add(entry);
                }
            }

            for (InteractionTurn turn : stmSlice.getInteractionTurns()) {
                if (turn == null) {
                    continue;
                }
                int bucketIndex = findSegmentBucket(baseSegments, turn.getTimestamp(), rangeStart, rangeEnd);
                if (bucketIndex >= 0) {
                    turnBuckets.get(bucketIndex).add(turn);
                }
            }
        }

        ArrayList<FixedActionSegment> fusedSegments = new ArrayList<>(baseSegments.size());
        for (int i = 0; i < baseSegments.size(); i++) {
            FixedActionSegment segment = baseSegments.get(i);
            ArrayList<TaggedContextEntry> mergedContextEntries = new ArrayList<>(contextBuckets.get(i));
            mergedContextEntries.sort(Comparator.comparingLong(TaggedContextEntry::getTimestamp));
            ArrayList<InteractionTurn> mergedTurns = new ArrayList<>(turnBuckets.get(i));
            mergedTurns.sort(Comparator.comparingLong(InteractionTurn::getTimestamp));
            CollectedContextSnapshot mergedSnapshot = CollectedContextSnapshot.fromEntries(
                    segment.getStartTimestamp(),
                    "stm_segment_merge",
                    mergedContextEntries);

            if (mergedSnapshot.isEmpty() && mergedTurns.isEmpty()) {
                fusedSegments.add(segment);
                continue;
            }

            FixedActionSegmentContext nextContext = segment.getContext()
                    .withMergedSnapshot(mergedSnapshot, List.copyOf(mergedTurns));
            fusedSegments.add(segment.withContext(nextContext));
        }
        return List.copyOf(fusedSegments);
    }

    private int findSegmentBucket(
            List<FixedActionSegment> segments,
            long timestamp,
            long rangeStart,
            long rangeEnd) {
        if (timestamp < rangeStart || timestamp >= rangeEnd) {
            return -1;
        }
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            if (timestamp >= segment.getStartTimestamp() && timestamp < segment.getEndTimestamp()) {
                return i;
            }
        }
        return -1;
    }

    private List<BoundaryAnchor> collectUniqueBoundaries() {
        if (timeline.segments().isEmpty() || timeline.splitPoints().isEmpty()) {
            return List.of();
        }

        ArrayList<BoundaryAnchor> boundaries = new ArrayList<>();
        Integer lastSplitSegmentIndex = null;
        for (EpisodeSplitPoint splitPoint : timeline.splitPoints()) {
            if (splitPoint == null) {
                continue;
            }
            int splitSegmentIndex = splitPoint.splitSegmentIndex();
            if (splitSegmentIndex < 0 || splitSegmentIndex > timeline.segmentCount()) {
                continue;
            }
            if (lastSplitSegmentIndex != null && splitSegmentIndex == lastSplitSegmentIndex) {
                continue;
            }
            boundaries.add(new BoundaryAnchor(splitSegmentIndex, Math.max(0L, splitPoint.splitTimestamp())));
            lastSplitSegmentIndex = splitSegmentIndex;
        }
        return List.copyOf(boundaries);
    }

    private Optional<EpisodeSynthesisTriggerSignal> buildClosedRegionSignal(
            BoundaryAnchor leftBoundary,
            BoundaryAnchor rightBoundary) {
        if (leftBoundary == null || rightBoundary == null) {
            return Optional.empty();
        }
        if (rightBoundary.splitSegmentIndex() <= leftBoundary.splitSegmentIndex()) {
            return Optional.empty();
        }

        int regionStartSegmentIndex = leftBoundary.splitSegmentIndex();
        int regionEndSegmentIndex = rightBoundary.splitSegmentIndex() - 1;
        FixedActionSegment startSegment = timeline.getSegment(regionStartSegmentIndex);
        FixedActionSegment endSegment = timeline.getSegment(regionEndSegmentIndex);
        if (startSegment == null || endSegment == null) {
            return Optional.empty();
        }

        long stmSliceExclusiveTimestamp = rightBoundary.splitTimestamp() > 0L
                ? rightBoundary.splitTimestamp()
                : Math.max(endSegment.getEndTimestamp(), startSegment.getStartTimestamp());
        return Optional.of(new EpisodeSynthesisTriggerSignal(
                leftBoundary.splitSegmentIndex(),
                regionStartSegmentIndex,
                regionEndSegmentIndex,
                rightBoundary.splitSegmentIndex(),
                startSegment.getStartTimestamp(),
                endSegment.getEndTimestamp(),
                stmSliceExclusiveTimestamp));
    }

    private record BoundaryAnchor(int splitSegmentIndex, long splitTimestamp) {
    }
}
