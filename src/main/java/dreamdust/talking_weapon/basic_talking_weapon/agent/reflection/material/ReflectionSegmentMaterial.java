package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ReflectionMaterial 内部稳定保留的 segment 单元。
 */
public record ReflectionSegmentMaterial(
        int localSegmentIndex,
        long segmentId,
        long startTimestamp,
        long endTimestamp,
        ReflectionContextSnapshot segmentSnapshot,
        ReflectionSegmentStateSnapshot segmentCloseStateSnapshot,
        List<TaggedContextEntry> localContextEntries,
        List<ReflectionPlayerAction> playerActions,
        List<ReflectionInteraction> interactions,
        List<ReflectionSegmentEvent> timelineEvents) {

    public ReflectionSegmentMaterial {
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
        if (segmentSnapshot == null) {
            throw new IllegalArgumentException("segmentSnapshot must not be null");
        }
        if (segmentCloseStateSnapshot == null) {
            throw new IllegalArgumentException("segmentCloseStateSnapshot must not be null");
        }
        localContextEntries = localContextEntries == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(localContextEntries));
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
