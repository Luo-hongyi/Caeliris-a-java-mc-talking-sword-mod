package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 附近实体采集器：扫描玩家周围一定半径内的实体，
 * 将<b>生物</b>（{@link LivingEntity}）与<b>其他实体</b>（掉落物、展示框、矿车等）
 * 分开统计，按类型分组计数后分别写入：
 * <ul>
 *   <li>{@code extras["nearby_creatures"]} — 生物（怪物、动物、NPC 等）</li>
 *   <li>{@code extras["nearby_objects"]}   — 非生物实体（掉落物、展示框、船、矿车等）</li>
 * </ul>
 *
 * <h3>可选功能（通过配置控制）</h3>
 * <ul>
 *   <li><b>视线检测</b> ({@code entity.lineOfSight}) — 仅对生物生效，过滤被方块遮挡的生物</li>
 *   <li><b>模糊数量</b> ({@code entity.fuzzyCount}) — 用"一个/一些/大量"代替精确数字</li>
 * </ul>
 *
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

        boolean lineOfSight = TalkingWeaponConfig.ENTITIES_LINE_OF_SIGHT.get();
        int losMaxCheck = TalkingWeaponConfig.ENTITIES_LOS_MAX_CHECK.get();
        boolean fuzzy = TalkingWeaponConfig.ENTITIES_FUZZY_COUNT.get();

        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> entities = level.getEntities(player, area);

        // 生物（LivingEntity，排除玩家自身）
        Map<String, Integer> creatureCounts = new LinkedHashMap<>();
        // 非生物实体（掉落物、展示框、矿车、船等）
        Map<String, Integer> objectCounts = new LinkedHashMap<>();

        // ── 视线检测预处理：仅对生物，按距离排序后取前 N 个 ──
        Vec3 eyePos = null;
        Set<Integer> occludedIds = Collections.emptySet();
        if (lineOfSight) {
            eyePos = player.getEyePosition(1.0F);
            List<Entity> creatures = new ArrayList<>();
            for (Entity e : entities) {
                if (e instanceof LivingEntity) creatures.add(e);
            }
            // 按距离排序
            final Vec3 pos = eyePos;
            creatures.sort(Comparator.comparingDouble(e -> e.distanceToSqr(pos)));
            // 仅对最近的 losMaxCheck 个做射线检测
            int limit = Math.min(creatures.size(), losMaxCheck);
            occludedIds = new HashSet<>();
            for (int i = 0; i < limit; i++) {
                Entity e = creatures.get(i);
                if (!hasLineOfSight(level, pos, e)) {
                    occludedIds.add(e.getId());
                }
            }
            // 超出检测上限的生物不做过滤（保守策略：假设可见）
        }

        for (Entity entity : entities) {
            // 视线检测仅过滤生物
            if (lineOfSight && entity instanceof LivingEntity && occludedIds.contains(entity.getId())) {
                continue;
            }

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
            builder.extra("nearby_creatures", formatCounts(creatureCounts, fuzzy));
        }

        // ── 非生物实体（受配置控制） ──
        if (!TalkingWeaponConfig.ENTITIES_ONLY_CREATURES.get()) {
            if (objectCounts.isEmpty()) {
                builder.extra("nearby_objects", "附近没有其他实体");
            } else {
                builder.extra("nearby_objects", formatCounts(objectCounts, fuzzy));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  射线检测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 使用射线追踪检测玩家眼部到实体中心之间是否有不透明方块遮挡。
     * 命中点距实体中心 < 1 格时容忍为"可见"。
     */
    private boolean hasLineOfSight(Level level, Vec3 eyePos, Entity entity) {
        Vec3 entityCenter = entity.getBoundingBox().getCenter();

        ClipContext context = new ClipContext(
                eyePos,
                entityCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null
        );
        BlockHitResult result = level.clip(context);

        if (result.getType() == HitResult.Type.MISS) {
            return true; // 无遮挡
        }
        // 命中点距目标中心 < 1 格，视为可见
        double hitDistSq = result.getLocation().distanceToSqr(entityCenter);
        return hitDistSq < 1.0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  格式化
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将 {名称 → 数量} 格式化为可读字符串。
     *
     * @param counts 分组计数
     * @param fuzzy  true 时使用模糊描述，false 时使用精确数量
     */
    private String formatCounts(Map<String, Integer> counts, boolean fuzzy) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((name, count) -> {
            if (sb.length() > 0) sb.append(", ");
            if (fuzzy) {
                sb.append(fuzzyQuantity(count)).append(name);
            } else {
                sb.append(name).append("×").append(count);
            }
        });
        return sb.toString();
    }

    /**
     * 将精确数量转换为模糊描述词。
     * <ul>
     *   <li>1       → "一个"</li>
     *   <li>2–5     → "一些"</li>
     *   <li>6+      → "大量"</li>
     * </ul>
     */
    private String fuzzyQuantity(int count) {
        if (count <= 1) return "一个";
        if (count <= 5) return "一些";
        return "大量";
    }
}
