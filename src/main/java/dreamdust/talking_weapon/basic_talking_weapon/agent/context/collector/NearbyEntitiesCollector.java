package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附近实体采集器：扫描玩家周围一定半径内的实体，
 * 将<b>生物</b>（{@link LivingEntity}）与<b>其他实体</b>（掉落物、展示框、矿车等）
 * 分开统计，按类型分组计数后分别写入：
 * <ul>
 *   <li>{@code extras["nearby_creatures"]} — 生物（怪物、动物、NPC 等）</li>
 *   <li>{@code extras["nearby_objects"]}   — 非生物实体（掉落物、展示框、船、矿车等）</li>
 * </ul>
 * 使用原版 {@link Level#getEntities(Entity, AABB)} API，无需任何外部依赖。
 */
public class NearbyEntitiesCollector implements IContextCollector {

    /** 默认扫描半径 (方块) */
    private final double radius;

    public NearbyEntitiesCollector() {
        this(16.0);
    }

    public NearbyEntitiesCollector(double radius) {
        this.radius = radius;
    }

    @Override
    public String getName() {
        return "nearby_entities";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null) return;

        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> entities = level.getEntities(player, area);

        // 生物（LivingEntity，排除玩家自身）
        Map<String, Integer> creatureCounts = new LinkedHashMap<>();
        // 非生物实体（掉落物、展示框、矿车、船等）
        Map<String, Integer> objectCounts = new LinkedHashMap<>();

        for (Entity entity : entities) {
            String name = entity.getType().getDescription().getString();
            if (entity instanceof LivingEntity) {
                creatureCounts.merge(name, 1, Integer::sum);
            } else {
                objectCounts.merge(name, 1, Integer::sum);
            }
        }

        // ── 生物 ──
        if (creatureCounts.isEmpty()) {
            builder.extra("nearby_creatures", "附近没有生物");
        } else {
            builder.extra("nearby_creatures", formatCounts(creatureCounts));
        }

        // ── 非生物实体（受配置控制） ──
        if (!TalkingWeaponConfig.ENTITIES_ONLY_CREATURES.get()) {
            if (objectCounts.isEmpty()) {
                builder.extra("nearby_objects", "附近没有其他实体");
            } else {
                builder.extra("nearby_objects", formatCounts(objectCounts));
            }
        }
    }

    /**
     * 将 {名称 → 数量} 格式化为 "名称×数量, ..." 字符串。
     */
    private String formatCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((name, count) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name).append("×").append(count);
        });
        return sb.toString();
    }
}
