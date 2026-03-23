package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

import java.util.Locale;

/**
 * 注意力元标签（Focus Tag）：Agent 认知雷达图的顶点。
 * <p>
 * 13 个扁平的一级类别，用于 LLM 计算注意力权重。
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

    /** 探索与位移：跑图、飞行、骑乘、划船、深入未知区域 */
    EXPLORE,

    /** 劳作与生存：挖矿、砍树、种田、钓鱼 */
    LABOR,

    /** 创造：合成物品、摆放方块、操作机器（如 Create 模组齿轮） */
    CREATION,

    /** 生物与动物：附近出现的生物实体（中性、友好、敌对） */
    CREATURE,

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
    TOOL,

    // ── 通用域 (Common Domain) ──

    /**
     * 通用上下文域。
     * 这是一个普通的、可调节的 FocusTag，不再隐式承担“准心强制加权”的职责。
     */
    COMMON;

    public static FocusTag parse(String raw) {
        FocusTag parsed = parseOrNull(raw);
        if (parsed == null) {
            throw new IllegalArgumentException("Unknown FocusTag: " + raw);
        }
        return parsed;
    }

    public static FocusTag parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return FocusTag.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
