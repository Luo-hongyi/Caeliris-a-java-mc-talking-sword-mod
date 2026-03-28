package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ReflectionMaterial 内部稳定保留的 segment 单元。
 */
public record ReflectionSegmentMaterial(
        int localSegmentIndex,
        int timelineSegmentIndex,
        long segmentId,
        long startTimestamp,
        long endTimestamp,
        ReflectionContextSnapshot startSnapshot,
        List<ReflectionPlayerAction> playerActions,
        List<ReflectionInteraction> interactions,
        List<ReflectionSegmentEvent> timelineEvents) {

    public ReflectionSegmentMaterial {
        if (localSegmentIndex < 0) {
            throw new IllegalArgumentException("localSegmentIndex must be >= 0");
        }
        if (timelineSegmentIndex < 0) {
            throw new IllegalArgumentException("timelineSegmentIndex must be >= 0");
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
        timelineEvents = timelineEvents == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(timelineEvents));
    }
}
