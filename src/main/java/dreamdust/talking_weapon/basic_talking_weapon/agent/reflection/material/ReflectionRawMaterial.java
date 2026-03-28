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
        long regionId,
        long startSegmentId,
        long endSegmentId,
        long leftBoundaryLeftSegmentId,
        long leftBoundaryRightSegmentId,
        long rightBoundaryLeftSegmentId,
        long rightBoundaryRightSegmentId,
        long startTimestamp,
        long endTimestamp,
        long stmSliceStartTimestamp,
        long stmSliceExclusiveTimestamp,
        List<ReflectionRawSegmentMaterial> segments) {

    public ReflectionRawMaterial {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (regionId <= 0L) {
            throw new IllegalArgumentException("regionId must be > 0");
        }
        if (startSegmentId <= 0L) {
            throw new IllegalArgumentException("startSegmentId must be > 0");
        }
        if (endSegmentId <= 0L) {
            throw new IllegalArgumentException("endSegmentId must be > 0");
        }
        if (startTimestamp < 0L) {
            throw new IllegalArgumentException("startTimestamp must be >= 0");
        }
        if (endTimestamp < startTimestamp) {
            throw new IllegalArgumentException("endTimestamp must be >= startTimestamp");
        }
        if (stmSliceStartTimestamp < 0L) {
            throw new IllegalArgumentException("stmSliceStartTimestamp must be >= 0");
        }
        if (stmSliceExclusiveTimestamp < stmSliceStartTimestamp) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= stmSliceStartTimestamp");
        }
        segments = segments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(segments));
    }
}
