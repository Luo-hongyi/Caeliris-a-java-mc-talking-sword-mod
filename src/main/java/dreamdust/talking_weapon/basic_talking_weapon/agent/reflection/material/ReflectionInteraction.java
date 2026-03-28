package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reflection material 中的 agent interaction。
 */
public record ReflectionInteraction(
        long timestamp,
        String triggerSource,
        String userPrompt,
        List<AgentOutputRecord> outputs,
        ReflectionContextSnapshot boundSnapshot) {

    public ReflectionInteraction {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must be >= 0");
        }
        triggerSource = triggerSource == null ? "" : triggerSource;
        userPrompt = userPrompt == null ? "" : userPrompt;
        outputs = outputs == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(outputs));
        if (boundSnapshot == null) {
            throw new IllegalArgumentException("boundSnapshot must not be null");
        }
    }
}
