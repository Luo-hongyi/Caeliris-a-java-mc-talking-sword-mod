package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

import java.util.Objects;

/**
 * 细节标签：{@code prefix:value} 格式的不可变值对象。
 * <p>
 * 细节标签贴近游戏底层数据，用于记忆检索和偏好匹配。
 * 前缀标识标签类型（如实体、物品、群系），值为具体的游戏 Registry ID 或状态枚举。
 * <p>
 * 设计原则：
 * <ul>
 * <li>标签由 Collector 在采集时自动生成，无需人工维护全量字典</li>
 * <li>{@code entity:}、{@code item:}、{@code block:} 等前缀直接使用 Minecraft
 * ResourceLocation</li>
 * <li>{@code trait:} 前缀利用 Forge/Vanilla 数据包标签实现跨模组泛化</li>
 * <li>{@code era:} 前缀为纯叙事纪元划分，不涉及精确时间戳</li>
 * </ul>
 */
public final class DetailTag {

    // ═══════════════════════════════════════════════════════════════
    // 前缀常量 (Prefix Constants)
    // ═══════════════════════════════════════════════════════════════

    /** 行为动词：attack, hurt, kill, eat, mine, craft, interact, move, sleep, death */
    public static final String ACTION = "action";

    /** 实体 Registry ID：minecraft:zombie, twilightforest:naga */
    public static final String ENTITY = "entity";

    /** 物品 Registry ID：minecraft:diamond_sword, farmersdelight:hamburger */
    public static final String ITEM = "item";

    /** 方块 Registry ID：minecraft:obsidian, minecraft:diamond_ore */
    public static final String BLOCK = "block";

    /** 抽象特征（来自 Forge/Vanilla Tags）：undead, boss, foods/meat, ores */
    public static final String TRAIT = "trait";

    /** 维度 ID：minecraft:overworld, minecraft:the_nether, minecraft:the_end */
    public static final String DIM = "dim";

    /** 群系 ID：minecraft:plains, minecraft:cherry_grove */
    public static final String BIOME = "biome";

    /** 量化状态分桶：health_critical, time_night, weather_rain, combat_intense */
    public static final String STATE = "state";

    /** 纯叙事纪元（不涉及精确时间）：forged, ancient_war, previous_owner, current_journey */
    public static final String ERA = "era";

    /** 玩家/Agent 共同维护的叙事地标：home, trial_grounds, first_base */
    public static final String LOC = "loc";

    /** 系统级功能方块 POI：nether_portal, mob_spawner, bed */
    public static final String POI = "poi";

    // ═══════════════════════════════════════════════════════════════
    // 实例字段
    // ═══════════════════════════════════════════════════════════════

    private final String prefix;
    private final String value;

    private DetailTag(String prefix, String value) {
        this.prefix = prefix;
        this.value = value;
    }

    // ═══════════════════════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建一个细节标签。
     *
     * @param prefix 前缀（推荐使用本类的常量，如 {@link #ENTITY}）
     * @param value  具体值（如 {@code "minecraft:zombie"}）
     * @return 不可变的 DetailTag 实例
     * @throws IllegalArgumentException 如果 prefix 或 value 为空
     */
    public static DetailTag of(String prefix, String value) {
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException("DetailTag prefix must not be empty");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("DetailTag value must not be empty");
        }
        return new DetailTag(prefix, value);
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    /** 获取前缀（如 {@code "entity"}, {@code "trait"}） */
    public String getPrefix() {
        return prefix;
    }

    /** 获取具体值（如 {@code "minecraft:zombie"}, {@code "boss"}） */
    public String getValue() {
        return value;
    }

    // ═══════════════════════════════════════════════════════════════
    // Object 方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 返回完整的标签字符串，格式为 {@code prefix:value}。
     * <p>
     * 示例：{@code "entity:minecraft:zombie"}, {@code "state:health_critical"}
     */
    @Override
    public String toString() {
        return prefix + ":" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DetailTag that))
            return false;
        return prefix.equals(that.prefix) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, value);
    }
}
