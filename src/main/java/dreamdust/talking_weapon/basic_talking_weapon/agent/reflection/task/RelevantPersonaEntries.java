package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前 reflection material 相关的 persona 条目集合。
 * <p>
 * 这些条目后续应通过 detail tag 召回，与 action 侧 persona recall 逻辑保持一致。
 */
public record RelevantPersonaEntries(List<PersonaEntry> entries) {

    public RelevantPersonaEntries {
        entries = entries == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
