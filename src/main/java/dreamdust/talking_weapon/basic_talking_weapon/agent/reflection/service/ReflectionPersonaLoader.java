package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionPersonaRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.RelevantPersonaEntries;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;

/**
 * 根据 material 和 request 召回 persona 条目。
 */
public interface ReflectionPersonaLoader {

    RelevantPersonaEntries load(
            ReflectionPersonaRequest request,
            ReflectionMaterial material,
            AgentPersona persona);
}
