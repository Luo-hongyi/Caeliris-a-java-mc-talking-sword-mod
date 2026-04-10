package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 附近实体采集器：扫描玩家周围一定半径内的实体，
 * 采用原生 EntityType 与 Tag 收集，防大包噪声机制。
 */
public class NearbyEntitiesCollector implements IContextCollector {

    /** 默认扫描半径 (方块) */
    private final double radius;

    public NearbyEntitiesCollector() {
        this(TalkingWeaponConfig.ENTITIES_SCAN_RADIUS.get());
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
        if (player == null || level == null)
            return;
        TaggedContextSource environmentSource = TaggedContextSource.environment();

        boolean lineOfSight = TalkingWeaponConfig.ENTITIES_LINE_OF_SIGHT.get();
        int losMaxCheck = TalkingWeaponConfig.ENTITIES_LOS_MAX_CHECK.get();
        boolean fuzzy = TalkingWeaponConfig.ENTITIES_FUZZY_COUNT.get();

        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> entities = level.getEntities(player, area);

        Map<String, Integer> creatureCounts = new LinkedHashMap<>();
        Map<String, Integer> objectCounts = new LinkedHashMap<>();
        Map<String, EntityType<?>> nameToType = new HashMap<>();
        Map<String, LivingEntity> nameToLiving = new HashMap<>();

        // ── 视线检测预处理 ──
        Vec3 eyePos = null;
        Set<Integer> occludedIds = Collections.emptySet();
        if (lineOfSight) {
            eyePos = player.getEyePosition(1.0F);
            List<Entity> creatures = new ArrayList<>();
            for (Entity e : entities) {
                if (e instanceof LivingEntity)
                    creatures.add(e);
            }
            final Vec3 pos = eyePos;
            creatures.sort(Comparator.comparingDouble(e -> e.distanceToSqr(pos)));
            int limit = Math.min(creatures.size(), losMaxCheck);
            occludedIds = new HashSet<>();
            for (int i = 0; i < limit; i++) {
                Entity e = creatures.get(i);
                if (!hasLineOfSight(level, pos, e)) {
                    occludedIds.add(e.getId());
                }
            }
        }

        hostileNames.clear();

        for (Entity entity : entities) {
            if (lineOfSight && entity instanceof LivingEntity && occludedIds.contains(entity.getId())) {
                continue;
            }

            String name = entity.getType().getDescription().getString();
            if (entity instanceof LivingEntity) {
                creatureCounts.merge(name, 1, Integer::sum);
                nameToType.putIfAbsent(name, entity.getType());
                nameToLiving.putIfAbsent(name, (LivingEntity) entity);
                // 判断是否敌对，使用原生的 MobCategory.MONSTER
                if (entity.getType().getCategory() == MobCategory.MONSTER) {
                    hostileNames.add(name);
                }
            } else {
                objectCounts.merge(name, 1, Integer::sum);
            }
        }

        // ── 记录文本描述 ──
        if (creatureCounts.isEmpty()) {
            builder.extra("nearby_creatures", "附近没有生物");
        } else {
            builder.extra("nearby_creatures", formatCounts(creatureCounts, fuzzy));
        }

        if (!TalkingWeaponConfig.ENTITIES_ONLY_CREATURES.get()) {
            if (objectCounts.isEmpty()) {
                builder.extra("nearby_objects", "附近没有其他实体");
            } else {
                builder.extra("nearby_objects", formatCounts(objectCounts, fuzzy));
            }
        }

        // ── 生成结构化标签条目 ──
        int totalHostile = 0;
        for (Map.Entry<String, Integer> entry : creatureCounts.entrySet()) {
            String name = entry.getKey();
            int count = entry.getValue();
            EntityType<?> type = nameToType.get(name);
            LivingEntity representative = nameToLiving.get(name);

            boolean isHostile = hostileNames.contains(name);
            if (isHostile) {
                totalHostile += count;
            }

            float imp = isHostile ? ImportanceRegistry.getInstance().get("native_specific:hostile_base", 0.6f) : ImportanceRegistry.getInstance().get("native_specific:passive_base", 0.2f);

            TaggedContextEntry.Builder tagBuilder = TaggedContextEntry.builder(environmentSource)
                    .description((fuzzy ? fuzzyQuantity(count) : count + "只") + name)
                    .importance(imp);

            if (isHostile) {
                tagBuilder.focus(FocusTag.CREATURE, FocusTag.HAZARD);
            } else {
                tagBuilder.focus(FocusTag.CREATURE);
            }

            // 单点原生标签 (Registry ID)
            if (type != null) {
                String registryId = ForgeRegistries.ENTITY_TYPES.getKey(type).toString();
                tagBuilder.detail(DetailTag.nativeSpecific(registryId));

                // 概括原生标签 (DataPack Tags)
                var reverseTagOption = ForgeRegistries.ENTITY_TYPES.tags().getReverseTag(type);
                if (reverseTagOption.isPresent()) {
                    for (var tagKey : reverseTagOption.get().getTagKeys().toList()) {
                        tagBuilder.detail(DetailTag.nativeGeneric(tagKey.location().toString()));
                    }
                }
            } else {
                // fallback
                tagBuilder.detail(DetailTag.nativeSpecific(name));
            }

            addSemanticTags(tagBuilder, representative, isHostile);
            builder.addTaggedEntry(tagBuilder.build());
        }

        if (totalHostile >= 5) {
            builder.addTaggedEntry(TaggedContextEntry.builder(environmentSource)
                    .focus(FocusTag.COMBAT, FocusTag.HAZARD)
                    .detail(DetailTag.customGeneric("combat_intense"))
                    .description("附近有大量敌对生物（" + totalHostile + "只），战斗激烈")
                    .importance(ImportanceRegistry.getInstance().get("native_specific:combat_intense", 0.9f))
                    .build());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private boolean hasLineOfSight(Level level, Vec3 eyePos, Entity entity) {
        Vec3 entityCenter = entity.getBoundingBox().getCenter();
        ClipContext context = new ClipContext(
                eyePos, entityCenter,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null);
        BlockHitResult result = level.clip(context);

        if (result.getType() == HitResult.Type.MISS) return true;
        return result.getLocation().distanceToSqr(entityCenter) < 1.0;
    }

    private String formatCounts(Map<String, Integer> counts, boolean fuzzy) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((name, count) -> {
            if (sb.length() > 0) sb.append(", ");
            if (fuzzy) sb.append(fuzzyQuantity(count)).append(name);
            else sb.append(name).append("×").append(count);
        });
        return sb.toString();
    }

    private String fuzzyQuantity(int count) {
        if (count <= 1) return "一个";
        if (count <= 5) return "一些";
        return "大量";
    }

    private void addSemanticTags(TaggedContextEntry.Builder tagBuilder, LivingEntity entity, boolean isHostile) {
        if (isHostile) {
            tagBuilder.detail(DetailTag.customGeneric("monster"));
        }
        if (entity == null) {
            return;
        }
        if (entity.isInvertedHealAndHarm()) {
            tagBuilder.detail(DetailTag.customGeneric("undead"));
        }
        if (entity.getMobType() == net.minecraft.world.entity.MobType.ARTHROPOD) {
            tagBuilder.detail(DetailTag.customGeneric("arthropod"));
        }
    }

    private final Set<String> hostileNames = new HashSet<>();
}
