package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

/**
 * Segment 内轻量事件流节点。
 */
public record ReflectionSegmentEvent(
        ReflectionSegmentEventType type,
        long timestamp,
        ReflectionContextSnapshot snapshot,
        TaggedContextEntry contextEntry,
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
