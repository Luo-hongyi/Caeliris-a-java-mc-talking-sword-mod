package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reflection material 中的玩家动作。
 */
public record ReflectionPlayerAction(
        long timestamp,
        ActionType actionType,
        String target,
        int count,
        Set<DetailTag> detailTags) {

    public ReflectionPlayerAction {
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must be >= 0");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        target = target == null ? "" : target;
        detailTags = detailTags == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(detailTags));
    }
}
