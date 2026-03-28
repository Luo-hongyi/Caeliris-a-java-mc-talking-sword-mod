package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reflection 侧正式输入的 root material。
 */
public record ReflectionMaterial(
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
        long interactionSliceExclusiveTimestamp,
        List<ReflectionSegmentMaterial> segments) {

    public ReflectionMaterial {
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
        if (rightBoundarySplitSegmentIndex < endTimelineSegmentIndex) {
            throw new IllegalArgumentException("rightBoundarySplitSegmentIndex must be >= endTimelineSegmentIndex");
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
