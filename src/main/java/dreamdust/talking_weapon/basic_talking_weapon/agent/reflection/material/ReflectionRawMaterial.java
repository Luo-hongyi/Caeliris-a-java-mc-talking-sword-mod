package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reflection exporter 输出的原始材料。
 * <p>
 * 它保留 exporter 从 episode / STM 中抽取出的原始结构，
 * 但还没有合并成统一事件流。
 */
public record ReflectionRawMaterial(
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
        long interactionSliceExclusiveTimestamp,
        List<ReflectionRawSegmentMaterial> segments) {

    public ReflectionRawMaterial {
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
        segments = segments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(segments));
    }
}
