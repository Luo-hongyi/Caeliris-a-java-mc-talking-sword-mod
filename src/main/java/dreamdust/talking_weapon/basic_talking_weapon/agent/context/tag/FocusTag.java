package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

/**
 * 注意力元标签（Focus Tag）：Agent 认知雷达图的顶点。
 * <p>
 * 11 个扁平的一级类别，用于 LLM 计算注意力权重。
 * 每个 {@link TaggedContextEntry} 携带一个或多个 FocusTag，
 * 表示该条目属于哪些注意力领域。
 * <p>
 * 后续迭代中，{@code AgentState} 会为每个 FocusTag 维护一个权重值，
 * 用于根据武器性格过滤和排序上下文信息。
 */
public enum FocusTag {

    // ── 行动域 (Action Domain) ──

    /** 战斗：攻击、受击、击杀等与伤害相关的行为 */
    COMBAT,

    /** 移动与探索：跑图、飞行、骑乘、划船 */
    MOVEMENT,

    /** 劳作与生存：挖矿、砍树、种田、钓鱼 */
    LABOR,

    /** 创造：合成物品、摆放方块、操作机器（如 Create 模组齿轮） */
    CREATION,

    /** 社交交互：与村民交易、驯服宠物、与其他玩家互动 */
    SOCIAL,

    // ── 信息域 (Information Domain) ──

    /** 风景与环境：所处群系、维度、天气变化 */
    LANDSCAPE,

    /** 危险与异状：岩浆、悬崖、黑暗、负面效果、被怪物凝视 */
    HAZARD,

    /** 自身状态：生命值、饥饿度、装备耐久度 */
    STATUS,

    // ── 物品域 (Item Domain) ──

    /** 消耗品：进食、喝药水 */
    CONSUMABLE,

    /** 方块交互：放置或破坏特殊方块 */
    BLOCK,

    /** 工具与武器：使用工具或武器 */
    TOOL
}
