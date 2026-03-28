package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionPromptDraft;

/**
 * 将结构化 reflection material 渲染为 task 内部 prompt draft。
 */
public interface ReflectionPromptFormatter {

    ReflectionPromptDraft format(ReflectionMaterial material);
}
