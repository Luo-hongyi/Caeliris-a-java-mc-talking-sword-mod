package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

/**
 * ReflectionTask 需要向 LLM 暴露哪些状态槽位。
 * <p>
 * 这里不再使用 CORE / FULL 之类的抽象等级，而是显式声明具体字段。
 */
public record ReflectionStateRequest(
        boolean includeIdentity,
        boolean includeRoleBackground,
        boolean includeRolePersonality,
        boolean includeEmotion,
        boolean includeFamiliarity,
        boolean includePlayerAddress,
        boolean includeCurrentDesire,
        boolean includeFavorability,
        boolean includePlayerProfile,
        boolean includeRecap) {
}
