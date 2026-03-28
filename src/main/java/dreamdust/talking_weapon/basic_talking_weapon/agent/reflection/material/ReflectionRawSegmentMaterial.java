package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raw material 内部保留的 segment 原始单元。
 */
public record ReflectionRawSegmentMaterial(
        int localSegmentIndex,
        long segmentId,
        long startTimestamp,
        long endTimestamp,
        ReflectionContextSnapshot startSnapshot,
        List<ReflectionPlayerAction> playerActions,
        List<ReflectionInteraction> interactions) {

    public ReflectionRawSegmentMaterial {
        if (localSegmentIndex < 0) {
            throw new IllegalArgumentException("localSegmentIndex must be >= 0");
        }
        if (segmentId <= 0L) {
            throw new IllegalArgumentException("segmentId must be > 0");
        }
        if (startTimestamp < 0L) {
            throw new IllegalArgumentException("startTimestamp must be >= 0");
        }
        if (endTimestamp < startTimestamp) {
            throw new IllegalArgumentException("endTimestamp must be >= startTimestamp");
        }
        if (startSnapshot == null) {
            throw new IllegalArgumentException("startSnapshot must not be null");
        }
        playerActions = playerActions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(playerActions));
        interactions = interactions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(interactions));
    }
}
