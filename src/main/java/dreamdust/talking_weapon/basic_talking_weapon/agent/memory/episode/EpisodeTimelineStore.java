package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeIntentMath;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeEpisodeTimelineFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Episode v2 的统一 store。
 * <p>
 * 当前第一版先承担：
 * <ul>
 * <li>持有最近一次 timeline 快照</li>
 * <li>作为 tracker 的唯一外部入口</li>
 * <li>未来只对外输出 episode synthesis trigger signals</li>
 * </ul>
 * <p>
 * 当前已开始在这里接 episode runtime state 恢复逻辑：
 * 若发生 replace-all 重建，会优先复用内存中的 region 状态；
 * 若当前内存为空，则退回读取 studio runtime timeline 中已持久化的 region 状态。
 * <p>
 * 这里也是 region runtime state 的 owner：
 * gate / holdback / signal / resolved / rollback 最终都应由这里收口。
 */
public final class EpisodeTimelineStore {

    private EpisodeTimeline timeline = EpisodeTimeline.empty();
    private final EpisodeTracker tracker = new EpisodeTracker();

    public synchronized EpisodeTimeline snapshot() {
        return timeline;
    }

    /**
     * episode 对外的正式 signal 输出接口。
     * <p>
     * 最终语义应为：
     * <ul>
     * <li>由 episode 内部判断哪些 closed region 已 ready</li>
     * <li>一次输出多个 region 级 signal，而不是一个总 signal</li>
     * <li>当前 region 模型下，默认 signal 数量应等于 {@code regions.size() - 2}</li>
     * <li>这个 holdback 数量后续应可调，用于保留最近一部分 region / STM 不立即推出，以维持上下文连贯性</li>
     * <li>也就是说，默认至少保留最后两个 region 窗口不直接发 signal</li>
     * <li>后续由对应 internal trigger 逐个提交后台 task</li>
     * </ul>
     * <p>
     * 当前 gate / pending / consumed / holdback 机制还没接回，因此先保留空列表骨架。
     */
    public synchronized List<EpisodeSynthesisTriggerSignal> collectEpisodeSynthesisTriggerSignals() {
        return List.of();
    }

    /**
     * reflection task 成功完成后，由 owner 将对应 region 标记为已解决。
     * <p>
     * 当前这里只冻结 region lifecycle 回写骨架；
     * 后续真正接 owner-side apply 时，还需要与 episode segment 丢弃 / STM truncate 一起提交。
     */
    public synchronized boolean markEpisodeSynthesisResolved(long regionId) {
        return updateRegionRuntimeState(
                regionId,
                null,
                EpisodeRegionLifecycleState.RESOLVED);
    }

    /**
     * reflection task 失败、取消或重启恢复后，由 owner 将对应 region 回滚到 HOLDING。
     * <p>
     * reject 分支的最终 owner-side cleanup 不会只做 lifecycle 改写，
     * 还需要同步抛出对应 episode segments，并截断同时间窗内的 STM。
     */
    public synchronized boolean rollbackEpisodeSynthesisToHolding(long regionId) {
        return updateRegionRuntimeState(
                regionId,
                null,
                EpisodeRegionLifecycleState.HOLDING);
    }

    public synchronized EpisodeTimeline replaceFixedSegments(List<FixedActionSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            tracker.reset();
            timeline = EpisodeTimeline.empty();
            return timeline;
        }
        timeline = tracker.replaceSegments(segments, collectRegionStateSeeds());
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
                ? tracker.replaceSegments(merged, collectRegionStateSeeds())
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
                && left.getRawEndIndex() == right.getRawEndIndex()
                && (left.getSegmentId() <= 0L
                        || right.getSegmentId() <= 0L
                        || left.getSegmentId() == right.getSegmentId());
    }

    private List<EpisodeRegion> collectRegionStateSeeds() {
        if (!timeline.regions().isEmpty()) {
            return timeline.regions();
        }
        return StudioRuntimeEpisodeTimelineFile.readPersistedRegions();
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
            boundaries.add(new BoundaryAnchor(
                    splitSegmentIndex,
                    splitPoint.splitLeftSegmentId(),
                    splitPoint.splitRightSegmentId(),
                    Math.max(0L, splitPoint.splitTimestamp())));
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
        long regionId = resolveRegionId(regionStartSegmentIndex, regionEndSegmentIndex);
        if (regionId <= 0L) {
            return Optional.empty();
        }
        return Optional.of(new EpisodeSynthesisTriggerSignal(
                regionId,
                leftBoundary.splitSegmentIndex(),
                leftBoundary.leftSegmentId(),
                leftBoundary.rightSegmentId(),
                regionStartSegmentIndex,
                startSegment.getSegmentId(),
                regionEndSegmentIndex,
                endSegment.getSegmentId(),
                rightBoundary.splitSegmentIndex(),
                rightBoundary.leftSegmentId(),
                rightBoundary.rightSegmentId(),
                startSegment.getStartTimestamp(),
                endSegment.getEndTimestamp(),
                stmSliceExclusiveTimestamp));
    }

    private record BoundaryAnchor(
            int splitSegmentIndex,
            long leftSegmentId,
            long rightSegmentId,
            long splitTimestamp) {
    }

    private long resolveRegionId(int regionStartSegmentIndex, int regionEndSegmentIndex) {
        long startSegmentId = timeline.getSegmentId(regionStartSegmentIndex);
        long endSegmentId = timeline.getSegmentId(regionEndSegmentIndex);
        for (EpisodeRegion region : timeline.regions()) {
            if (region == null) {
                continue;
            }
            if (startSegmentId > 0L
                    && endSegmentId > 0L
                    && region.startSegmentId() == startSegmentId
                    && region.endSegmentId() == endSegmentId) {
                return region.regionId();
            }
            if (region.startSegmentIndex() == regionStartSegmentIndex
                    && region.endSegmentIndex() == regionEndSegmentIndex) {
                return region.regionId();
            }
        }
        return 0L;
    }

    private boolean updateRegionRuntimeState(
            long regionId,
            EpisodeRegionGateDecision nextGateDecision,
            EpisodeRegionLifecycleState nextLifecycleState) {
        if (regionId <= 0L || timeline.regions().isEmpty()) {
            return false;
        }

        ArrayList<EpisodeRegion> nextRegions = new ArrayList<>(timeline.regions().size());
        boolean updated = false;
        for (EpisodeRegion region : timeline.regions()) {
            if (region == null || region.regionId() != regionId) {
                nextRegions.add(region);
                continue;
            }

            EpisodeRegionGateDecision gateDecision = nextGateDecision == null
                    ? region.gateDecision()
                    : nextGateDecision;
            EpisodeRegionLifecycleState lifecycleState = nextLifecycleState == null
                    ? region.lifecycleState()
                    : nextLifecycleState;

            if (lifecycleState == EpisodeRegionLifecycleState.ACTIVE) {
                gateDecision = EpisodeRegionGateDecision.UNSET;
            }

            nextRegions.add(region.withRuntimeState(gateDecision, lifecycleState));
            updated = true;
        }

        if (!updated) {
            return false;
        }

        timeline = new EpisodeTimeline(
                timeline.segments(),
                nextRegions,
                timeline.pendingMarks(),
                timeline.splitPoints());
        return true;
    }
}
