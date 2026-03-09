package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附近实体采集器：扫描玩家周围一定半径内的实体，
 * 按类型分组计数后写入 extras["nearby_entities"]。
 * <p>
 * 使用原版 {@link Level#getEntities(Entity, AABB)} API，
 * 无需任何外部依赖。
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
        if (!TalkingWeaponConfig.ENABLE_NEARBY_ENTITIES.get()) return;
        if (player == null || level == null) return;

        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> entities = level.getEntities(player, area);

        if (entities.isEmpty()) {
            builder.extra("nearby_entities", "周围没有生物");
            return;
        }

        // 按实体类型名称分组计数
        Map<String, Integer> entityCounts = new LinkedHashMap<>();
        for (Entity entity : entities) {
            String name = entity.getType().getDescription().getString();
            entityCounts.merge(name, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder();
        entityCounts.forEach((name, count) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name).append("×").append(count);
        });

        builder.extra("nearby_entities", sb.toString());
    }
}
