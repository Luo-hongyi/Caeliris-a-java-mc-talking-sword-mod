package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;

/**
 * 将 raw material 合并成正式 reflection material。
 */
public interface ReflectionMaterialMerger {

    ReflectionMaterial merge(ReflectionRawMaterial rawMaterial);
}
