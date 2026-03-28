package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;

/**
 * Reflection task 对 material 的读取请求。
 */
public record ReflectionMaterialRequest(
        ReflectionScope scope,
        int startTimelineIndex,
        long startSegmentId,
        int endTimelineIndex,
        long endSegmentId,
        int leftBoundarySplitTimelineIndex,
        long leftBoundaryLeftSegmentId,
        long leftBoundaryRightSegmentId,
        int rightBoundarySplitTimelineIndex,
        long rightBoundaryLeftSegmentId,
        long rightBoundaryRightSegmentId,
        long startTimestamp,
        long endTimestamp,
        long interactionSliceExclusiveTimestamp) {

    public ReflectionMaterialRequest {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (startTimelineIndex < 0) {
            throw new IllegalArgumentException("startTimelineIndex must be >= 0");
        }
        if (endTimelineIndex < startTimelineIndex) {
            throw new IllegalArgumentException("endTimelineIndex must be >= startTimelineIndex");
        }
        if (leftBoundarySplitTimelineIndex < 0) {
            throw new IllegalArgumentException("leftBoundarySplitTimelineIndex must be >= 0");
        }
        if (rightBoundarySplitTimelineIndex <= endTimelineIndex) {
            throw new IllegalArgumentException("rightBoundarySplitTimelineIndex must be > endTimelineIndex");
        }
        if (startTimestamp < 0L) {
            throw new IllegalArgumentException("startTimestamp must be >= 0");
        }
        if (endTimestamp < startTimestamp) {
            throw new IllegalArgumentException("endTimestamp must be >= startTimestamp");
        }
        if (interactionSliceExclusiveTimestamp < 0L) {
            throw new IllegalArgumentException("interactionSliceExclusiveTimestamp must be >= 0");
        }
    }

    public static ReflectionMaterialRequest forEpisodeSynthesisSignal(EpisodeSynthesisTriggerSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        return new ReflectionMaterialRequest(
                ReflectionScope.REGION,
                signal.regionStartTimelineIndex(),
                signal.regionStartSegmentId(),
                signal.regionEndTimelineIndex(),
                signal.regionEndSegmentId(),
                signal.leftBoundarySplitTimelineIndex(),
                signal.leftBoundaryLeftSegmentId(),
                signal.leftBoundaryRightSegmentId(),
                signal.rightBoundarySplitTimelineIndex(),
                signal.rightBoundaryLeftSegmentId(),
                signal.rightBoundaryRightSegmentId(),
                signal.regionStartTimestamp(),
                signal.regionEndTimestamp(),
                signal.stmSliceExclusiveTimestamp());
    }
}
