package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;

/**
 * Reflection task 对 material 的读取请求。
 */
public record ReflectionMaterialRequest(
        ReflectionScope scope,
        int startTimelineSegmentIndex,
        long startSegmentId,
        int endTimelineSegmentIndex,
        long endSegmentId,
        int leftBoundarySplitSegmentIndex,
        long leftBoundaryLeftSegmentId,
        long leftBoundaryRightSegmentId,
        int rightBoundarySplitSegmentIndex,
        long rightBoundaryLeftSegmentId,
        long rightBoundaryRightSegmentId,
        long startTimestamp,
        long endTimestamp,
        long interactionSliceExclusiveTimestamp) {

    public ReflectionMaterialRequest {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (startTimelineSegmentIndex < 0) {
            throw new IllegalArgumentException("startTimelineSegmentIndex must be >= 0");
        }
        if (endTimelineSegmentIndex < startTimelineSegmentIndex) {
            throw new IllegalArgumentException("endTimelineSegmentIndex must be >= startTimelineSegmentIndex");
        }
        if (leftBoundarySplitSegmentIndex < 0) {
            throw new IllegalArgumentException("leftBoundarySplitSegmentIndex must be >= 0");
        }
        if (rightBoundarySplitSegmentIndex <= endTimelineSegmentIndex) {
            throw new IllegalArgumentException("rightBoundarySplitSegmentIndex must be > endTimelineSegmentIndex");
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
                signal.regionStartSegmentIndex(),
                signal.regionStartSegmentId(),
                signal.regionEndSegmentIndex(),
                signal.regionEndSegmentId(),
                signal.leftBoundarySplitSegmentIndex(),
                signal.leftBoundaryLeftSegmentId(),
                signal.leftBoundaryRightSegmentId(),
                signal.rightBoundarySplitSegmentIndex(),
                signal.rightBoundaryLeftSegmentId(),
                signal.rightBoundaryRightSegmentId(),
                signal.regionStartTimestamp(),
                signal.regionEndTimestamp(),
                signal.stmSliceExclusiveTimestamp());
    }
}
