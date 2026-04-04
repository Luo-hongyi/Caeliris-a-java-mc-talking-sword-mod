package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemorySourceMaterialCleanupPlan;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemorySourceMaterialCleanupReason;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemorySourceMaterialWindow;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate.EpisodeSynthesisGate;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate.EpisodeSynthesisGateInput;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate.EpisodeSynthesisGateResult;
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
     * 当前只放行已经满足下面条件的 region：
     * <ul>
     * <li>`gateDecision = PASS`</li>
     * <li>`lifecycleState = HOLDING`</li>
     * <li>已经越过 holdback 窗口</li>
     * </ul>
     * signal 发出后，对应 region 会立即推进为 `PENDING`，避免重复发出。
     */
    public synchronized List<EpisodeSynthesisTriggerSignal> collectEpisodeSynthesisTriggerSignals() {
        if (timeline.regions().isEmpty()) {
            return List.of();
        }

        List<EpisodeRegion> regionSnapshot = timeline.regions();
        int holdbackRegionCount = EpisodeRuntimeSettings.getSignalHoldbackRegionCount();
        int readyRegionExclusive = Math.max(0, regionSnapshot.size() - holdbackRegionCount);
        if (readyRegionExclusive <= 0) {
            return List.of();
        }

        ArrayList<EpisodeSynthesisTriggerSignal> signals = new ArrayList<>();
        for (int i = 0; i < readyRegionExclusive; i++) {
            EpisodeRegion region = regionSnapshot.get(i);
            if (!isReadyForSynthesisSignal(region)) {
                continue;
            }

            EpisodeSynthesisTriggerSignal signal = buildSynthesisTriggerSignal(region);
            if (signal == null) {
                continue;
            }

            signals.add(signal);
            updateRegionRuntimeState(
                    region.regionId(),
                    null,
                    EpisodeRegionLifecycleState.PENDING);
        }
        return List.copyOf(signals);
    }

    /**
     * 当前第一阶段只先接入 gate reject。
     * <p>
     * 行为固定为：
     * <ul>
     * <li>只遍历 closed region，即当前处于 `HOLDING + UNSET` 的 region</li>
     * <li>PASS 结果先缓存到 region 自身，后续 signal / task 提交链路再接</li>
     * <li>REJECT 结果立即返回给 owner，由 owner 统一执行 cleanup</li>
     * </ul>
     */
    public synchronized Optional<EpisodeSynthesisGateInput> pollEpisodeGateRejectInput(
            STMPool stmPool,
            EpisodeSynthesisGate gate) {
        if (stmPool == null || gate == null || timeline.regions().isEmpty()) {
            return Optional.empty();
        }

        for (int i = 0; i < timeline.regions().size(); i++) {
            EpisodeRegion region = timeline.regions().get(i);
            if (region == null) {
                continue;
            }
            if (region.lifecycleState() != EpisodeRegionLifecycleState.HOLDING
                    || region.gateDecision() != EpisodeRegionGateDecision.UNSET) {
                continue;
            }

            EpisodeRegion nextRegion = i + 1 < timeline.regions().size()
                    ? timeline.regions().get(i + 1)
                    : null;
            EpisodeSynthesisGateInput input = buildGateInput(region, nextRegion, stmPool);
            if (input == null) {
                continue;
            }

            EpisodeSynthesisGateResult result = gate.evaluate(input);
            if (result == null) {
                continue;
            }

            updateRegionRuntimeState(
                    region.regionId(),
                    result.gateDecision(),
                    EpisodeRegionLifecycleState.HOLDING);
            if (result.gateDecision() == EpisodeRegionGateDecision.REJECT) {
                return Optional.of(input);
            }
        }
        return Optional.empty();
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

    public synchronized boolean hasRegion(long regionId) {
        return findRegion(regionId) != null;
    }

    public synchronized boolean canApplySourceMaterialCleanupPlan(MemorySourceMaterialCleanupPlan plan) {
        if (plan == null || plan.window() == null) {
            return false;
        }
        EpisodeRegion region = findRegion(plan.regionId());
        if (region == null || !matchesRegionWindow(region, plan.window())) {
            return false;
        }
        if (plan.reason() == MemorySourceMaterialCleanupReason.EPISODE_GATE_REJECT) {
            return region.gateDecision() == EpisodeRegionGateDecision.REJECT
                    && region.lifecycleState() == EpisodeRegionLifecycleState.HOLDING;
        }
        if (plan.reason() == MemorySourceMaterialCleanupReason.EPISODE_SYNTHESIS_CONSUMED) {
            return region.gateDecision() == EpisodeRegionGateDecision.PASS
                    && region.lifecycleState() == EpisodeRegionLifecycleState.PENDING;
        }
        return false;
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
        return left.sameStableIdentity(right);
    }

    private List<EpisodeRegion> collectRegionStateSeeds() {
        if (!timeline.regions().isEmpty()) {
            return timeline.regions();
        }
        return StudioRuntimeEpisodeTimelineFile.readPersistedRegions();
    }

    private boolean isReadyForSynthesisSignal(EpisodeRegion region) {
        if (region == null) {
            return false;
        }
        if (region.gateDecision() != EpisodeRegionGateDecision.PASS) {
            return false;
        }
        return region.lifecycleState() == EpisodeRegionLifecycleState.HOLDING;
    }

    private EpisodeSynthesisTriggerSignal buildSynthesisTriggerSignal(EpisodeRegion region) {
        if (region == null) {
            return null;
        }

        if (timeline.indexOfSegmentId(region.startSegmentId()) < 0
                || timeline.indexOfSegmentId(region.endSegmentId()) < 0) {
            return null;
        }

        BoundaryAnchor leftBoundary = resolveBoundaryAt(
                timeline.boundaryBeforeSegment(region.startSegmentId()),
                Math.max(0L, region.startTimestamp()));
        BoundaryAnchor rightBoundary = resolveBoundaryAt(
                timeline.boundaryAfterSegment(region.endSegmentId()),
                Math.max(0L, region.endTimestamp()));
        if (leftBoundary == null || rightBoundary == null) {
            return null;
        }

        long stmSliceStartTimestamp = Math.max(0L, region.startTimestamp());
        long stmSliceExclusiveTimestamp = Math.max(
                stmSliceStartTimestamp,
                rightBoundary.splitTimestamp());
        return new EpisodeSynthesisTriggerSignal(
                region.regionId(),
                leftBoundary.leftSegmentId(),
                leftBoundary.rightSegmentId(),
                region.startSegmentId(),
                region.endSegmentId(),
                rightBoundary.leftSegmentId(),
                rightBoundary.rightSegmentId(),
                region.startTimestamp(),
                region.endTimestamp(),
                stmSliceStartTimestamp,
                stmSliceExclusiveTimestamp);
    }

    private BoundaryAnchor resolveBoundaryAt(
            EpisodeTimeline.BoundarySegmentIds boundarySegmentIds,
            long fallbackTimestamp) {
        if (boundarySegmentIds == null) {
            return null;
        }
        EpisodeSplitPoint splitPoint = findSplitPointAt(
                boundarySegmentIds.leftSegmentId(),
                boundarySegmentIds.rightSegmentId());
        if (splitPoint != null) {
            long safeTimestamp = splitPoint.splitTimestamp() > 0L
                    ? splitPoint.splitTimestamp()
                    : Math.max(0L, fallbackTimestamp);
            return new BoundaryAnchor(
                    splitPoint.splitLeftSegmentId(),
                    splitPoint.splitRightSegmentId(),
                    safeTimestamp);
        }
        return new BoundaryAnchor(
                boundarySegmentIds.leftSegmentId(),
                boundarySegmentIds.rightSegmentId(),
                Math.max(0L, fallbackTimestamp));
    }

    private EpisodeSplitPoint findSplitPointAt(long leftSegmentId, long rightSegmentId) {
        for (EpisodeSplitPoint splitPoint : timeline.splitPoints()) {
            if (splitPoint == null) {
                continue;
            }
            if (splitPoint.splitLeftSegmentId() == leftSegmentId
                    && splitPoint.splitRightSegmentId() == rightSegmentId) {
                return splitPoint;
            }
        }
        return null;
    }

    private EpisodeSynthesisGateInput buildGateInput(
            EpisodeRegion region,
            EpisodeRegion nextRegion,
            STMPool stmPool) {
        if (region == null || stmPool == null) {
            return null;
        }
        List<FixedActionSegment> regionSegments = timeline.sliceSegmentsById(
                region.startSegmentId(),
                region.endSegmentId());
        if (regionSegments.isEmpty()) {
            return null;
        }

        long stmSliceStartTimestamp = Math.max(0L, region.startTimestamp());
        long stmSliceExclusiveTimestamp;
        if (nextRegion != null && nextRegion.startTimestamp() > region.startTimestamp()) {
            stmSliceExclusiveTimestamp = nextRegion.startTimestamp();
        } else {
            stmSliceExclusiveTimestamp = Math.max(0L, region.endTimestamp());
        }

        return new EpisodeSynthesisGateInput(
                region,
                regionSegments,
                stmPool.snapshotBetween(stmSliceStartTimestamp, stmSliceExclusiveTimestamp),
                stmSliceStartTimestamp,
                stmSliceExclusiveTimestamp);
    }

    private EpisodeRegion findRegion(long regionId) {
        if (regionId <= 0L || timeline.regions().isEmpty()) {
            return null;
        }
        for (EpisodeRegion region : timeline.regions()) {
            if (region != null && region.regionId() == regionId) {
                return region;
            }
        }
        return null;
    }

    private boolean matchesRegionWindow(EpisodeRegion region, MemorySourceMaterialWindow window) {
        if (region == null || window == null) {
            return false;
        }
        return region.startSegmentId() == window.startSegmentId()
                && region.endSegmentId() == window.endSegmentId()
                && region.startTimestamp() == window.startTimestamp()
                && region.endTimestamp() == window.endTimestamp();
    }

    private record BoundaryAnchor(
            long leftSegmentId,
            long rightSegmentId,
            long splitTimestamp) {
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
