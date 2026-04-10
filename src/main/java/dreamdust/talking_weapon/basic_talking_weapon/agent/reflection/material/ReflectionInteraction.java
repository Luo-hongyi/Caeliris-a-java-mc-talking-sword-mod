package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reflection material 中的 agent interaction。
 */
public record ReflectionInteraction(
        long timestamp,
        String triggerSource,
        List<InteractionMessage> messages,
        ReflectionContextSnapshot boundSnapshot) {

    public ReflectionInteraction {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must be >= 0");
        }
        triggerSource = triggerSource == null ? "" : triggerSource;
        messages = messages == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        if (boundSnapshot == null) {
            throw new IllegalArgumentException("boundSnapshot must not be null");
        }
    }
}
