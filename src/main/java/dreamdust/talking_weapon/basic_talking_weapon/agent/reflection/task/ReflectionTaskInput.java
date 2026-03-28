package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;

import java.util.Objects;

/**
 * 具体 reflection task 在 LLM 前看到的固定事实输入。
 * <p>
 * 注意这里故意不带 prompt draft，
 * 因为 prompt draft 只是 material 的内部渲染产物，不是并列事实源。
 */
public record ReflectionTaskInput(
        ReflectionMaterial material,
        ReflectionStateSnapshot state,
        RelevantPersonaEntries relevantPersonaEntries) {

    public ReflectionTaskInput {
        material = Objects.requireNonNull(material, "material");
        state = Objects.requireNonNull(state, "state");
        relevantPersonaEntries = Objects.requireNonNull(relevantPersonaEntries, "relevantPersonaEntries");
    }
}
