package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection 侧使用的轻量上下文快照。
 */
public record ReflectionContextSnapshot(
        List<TaggedContextEntry> visibleContexts,
        Map<String, String> contextExtras) {

    public ReflectionContextSnapshot {
        visibleContexts = visibleContexts == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(visibleContexts));
        contextExtras = contextExtras == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(contextExtras));
    }
}
