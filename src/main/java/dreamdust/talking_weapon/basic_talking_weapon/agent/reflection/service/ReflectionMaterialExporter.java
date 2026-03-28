package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterialRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;

/**
 * 从 episode / STM 导出 reflection raw material。
 */
public interface ReflectionMaterialExporter {

    ReflectionRawMaterial export(ReflectionMaterialRequest request, AgentMemory memory);
}
