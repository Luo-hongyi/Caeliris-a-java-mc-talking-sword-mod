package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

/**
 * 玩家阶段性目的枚举。
 * <p>
 * 这一层服务于 episode 分割和长期记忆主题约束，
 * 不是对单个动作的逐条标签化。
 */
public enum PlayerIntent {
    GATHER,
    TRAVEL,
    EXPLORE,
    COMBAT,
    BUILD,
    CRAFT,
    SOCIAL
}
