package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionPlayerAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionPersonaRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.RelevantPersonaEntries;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaEntry;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 第一版 persona loader。
 * <p>
 * 当前先基于 detail tag 与 always_recalled 做轻量召回。
 */
public final class DefaultReflectionPersonaLoader implements ReflectionPersonaLoader {

    @Override
    public RelevantPersonaEntries load(
            ReflectionPersonaRequest request,
            ReflectionMaterial material,
            AgentPersona persona) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(persona, "persona");

        Set<String> materialDetailTags = collectMaterialDetailTags(material);
        Map<String, PersonaEntry> matched = new LinkedHashMap<>();

        for (PersonaEntry entry : persona.getPersonaEntries()) {
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            boolean include = false;
            if (request.includeAlwaysRecalledEntries() && entry.isAlwaysRecalled()) {
                include = true;
            }
            if (!include && request.includeMaterialMatchedEntries() && !materialDetailTags.isEmpty()) {
                for (String detailTag : entry.getDetailTags()) {
                    if (materialDetailTags.contains(detailTag)) {
                        include = true;
                        break;
                    }
                }
            }
            if (include) {
                matched.put(entry.getId(), entry);
            }
        }

        return new RelevantPersonaEntries(matched.values().stream().toList());
    }

    private Set<String> collectMaterialDetailTags(ReflectionMaterial material) {
        Set<String> tags = new LinkedHashSet<>();
        for (ReflectionSegmentMaterial segment : material.segments()) {
            if (segment == null) {
                continue;
            }
            for (ReflectionPlayerAction action : segment.playerActions()) {
                if (action == null) {
                    continue;
                }
                for (DetailTag detailTag : action.detailTags()) {
                    if (detailTag != null) {
                        tags.add(detailTag.toString());
                    }
                }
            }
        }
        return tags;
    }
}
