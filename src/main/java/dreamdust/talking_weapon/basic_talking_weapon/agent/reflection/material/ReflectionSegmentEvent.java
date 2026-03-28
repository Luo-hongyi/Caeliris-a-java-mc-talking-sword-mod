package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

/**
 * Segment 内轻量事件流节点。
 */
public record ReflectionSegmentEvent(
        ReflectionSegmentEventType type,
        long timestamp,
        ReflectionContextSnapshot snapshot,
        ReflectionPlayerAction playerAction,
        ReflectionInteraction interaction) {

    public ReflectionSegmentEvent {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must be >= 0");
        }
    }
}
