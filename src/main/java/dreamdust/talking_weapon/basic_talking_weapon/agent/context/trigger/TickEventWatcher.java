package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentSensor;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionBuffer;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 统一事件感知器：监听 Forge 游戏事件并写入 {@link ActionBuffer}。
 * <p>
 * 职责：
 * <ol>
 * <li>定时检测群系变化（原有功能）</li>
 * <li>监听战斗事件（攻击、受伤、击杀）</li>
 * <li>监听资源事件（挖矿、使用物品/进食）</li>
 * <li>锚点位移检测（长途旅行）</li>
 * </ol>
 * <p>
 * 所有新增的 {@code @SubscribeEvent} 方法仅做一件事：向 ActionBuffer 写入记录。
 * 不触发 LLM、不创建 Task。
 */
public class TickEventWatcher {

    /** 群系/旅行检测间隔 (tick)，20 tick ≈ 1 秒 */
    private static final int CHECK_INTERVAL_TICKS = 100; // 每 5 秒

    /** 旅行触发距离阈值 (blocks) */
    private static final double TRAVEL_DISTANCE_THRESHOLD = 200.0;

    /** 旅行触发垂直距离阈值 (blocks) */
    private static final double TRAVEL_VERTICAL_THRESHOLD = 50.0;

    private int tickCounter = 0;

    // ── 群系变化检测状态 ──
    private String lastKnownBiome = null;

    // ── 锚点位移检测状态 ──
    private BlockPos anchorPos = null;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (!TalkingWeaponConfig.ENABLE_PROACTIVE_TRIGGERS.get())
            return;

        tickCounter++;
        if (tickCounter < CHECK_INTERVAL_TICKS)
            return;
        tickCounter = 0;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null)
            return;

        ActionBuffer buffer = AgentRuntime.getInstance().getMemory().getActionBuffer();

        // ── 群系变化检测 ──
        if (TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get()) {
            checkBiomeChange(player, level, buffer);
        }

        // ── 锚点位移检测（旅行） ──
        checkTravel(player, buffer);
    }

    // ═══════════════════════════════════════════════════════════════
    // 战斗事件
    // ═══════════════════════════════════════════════════════════════

    /**
     * 玩家攻击其他实体。
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        LivingEntity hurt = event.getEntity();
        Player player = mc.player;

        // 玩家攻击别人
        if (event.getSource().getEntity() == player) {
            String targetId = entityId(hurt);
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ENTITY, targetId));
            addTraitTags(hurt, tags);
            AgentRuntime.getInstance().getMemory().getActionBuffer()
                    .add(ActionRecord.of(ActionType.ATTACK, targetId, tags));
        }
        // 玩家被攻击
        else if (hurt == player) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ENTITY, sourceId));
            AgentRuntime.getInstance().getMemory().getActionBuffer()
                    .add(ActionRecord.of(ActionType.HURT_BY, sourceId, tags));
        }
    }

    /**
     * 实体死亡（玩家击杀目标）。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        LivingEntity dead = event.getEntity();

        // 玩家击杀了目标
        if (event.getSource().getEntity() == mc.player && dead != mc.player) {
            String targetId = entityId(dead);
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ENTITY, targetId));
            addTraitTags(dead, tags);
            AgentRuntime.getInstance().getMemory().getActionBuffer()
                    .add(ActionRecord.of(ActionType.KILL, targetId, tags));
        }
        // 玩家自己死亡
        else if (dead == mc.player) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            AgentRuntime.getInstance().getMemory().getActionBuffer()
                    .add(ActionRecord.of(ActionType.DEATH, sourceId));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 资源事件
    // ═══════════════════════════════════════════════════════════════

    /**
     * 玩家破坏方块。
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        if (event.getPlayer() != mc.player)
            return;

        BlockState state = event.getState();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String id = blockId != null ? blockId.toString() : "unknown";

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.of(DetailTag.BLOCK, id));

        AgentRuntime.getInstance().getMemory().getActionBuffer()
                .add(ActionRecord.of(ActionType.MINE_BLOCK, id, tags));
    }

    /**
     * 玩家使用物品完毕（进食/喝药水等）。
     */
    @SubscribeEvent
    public void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        if (event.getEntity() != mc.player)
            return;

        ItemStack stack = event.getItem();
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String id = itemId != null ? itemId.toString() : "unknown";

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.of(DetailTag.ITEM, id));

        // 判断是否为食物
        ActionType type = stack.getItem().isEdible() ? ActionType.EAT : ActionType.USE_ITEM;

        AgentRuntime.getInstance().getMemory().getActionBuffer()
                .add(ActionRecord.of(type, id, tags));
    }

    // ═══════════════════════════════════════════════════════════════
    // 群系变化检测（保留原有逻辑，同时写入 ActionBuffer）
    // ═══════════════════════════════════════════════════════════════

    private void checkBiomeChange(Player player, Level level, ActionBuffer buffer) {
        Holder<Biome> biomeHolder = level.getBiome(player.blockPosition());
        String currentBiome = biomeHolder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");

        if (lastKnownBiome == null) {
            lastKnownBiome = currentBiome;
            return;
        }

        if (!currentBiome.equals(lastKnownBiome)) {
            String oldBiome = lastKnownBiome;
            lastKnownBiome = currentBiome;

            // 写入 ActionBuffer
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.BIOME, currentBiome));
            buffer.add(ActionRecord.of(ActionType.BIOME_CHANGE, currentBiome, tags));

            // 原有的 LLM 触发逻辑（fire BiomeChangeTrigger）
            if (AgentSensor.getInstance().isRuntimeBusy())
                return;
            BiomeChangeTrigger trigger = new BiomeChangeTrigger(oldBiome, currentBiome);
            AgentSensor.getInstance().fireTrigger(trigger, player);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 锚点位移检测（旅行）
    // ═══════════════════════════════════════════════════════════════

    private void checkTravel(Player player, ActionBuffer buffer) {
        BlockPos currentPos = player.blockPosition();

        if (anchorPos == null) {
            anchorPos = currentPos;
            return;
        }

        double dx = currentPos.getX() - anchorPos.getX();
        double dz = currentPos.getZ() - anchorPos.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double verticalDist = Math.abs(currentPos.getY() - anchorPos.getY());

        if (horizontalDist > TRAVEL_DISTANCE_THRESHOLD || verticalDist > TRAVEL_VERTICAL_THRESHOLD) {
            // 生成 TRAVEL 记录
            String direction = String.format("(%d,%d,%d)→(%d,%d,%d)",
                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(),
                    currentPos.getX(), currentPos.getY(), currentPos.getZ());

            Set<DetailTag> tags = new LinkedHashSet<>();
            // 将当前群系和维度写入标签
            if (lastKnownBiome != null) {
                tags.add(DetailTag.of(DetailTag.BIOME, lastKnownBiome));
            }

            buffer.add(ActionRecord.of(ActionType.TRAVEL, direction, tags));

            // 重置锚点
            anchorPos = currentPos;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 获取实体的 Registry ID */
    private String entityId(LivingEntity entity) {
        ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return rl != null ? rl.toString() : "unknown";
    }

    /** 为实体添加 trait 标签（亡灵、节肢、Boss 等） */
    private void addTraitTags(LivingEntity entity, Set<DetailTag> tags) {
        if (entity.isInvertedHealAndHarm()) {
            tags.add(DetailTag.of(DetailTag.TRAIT, "undead"));
        }
        // Forge MobType check for arthropod
        if (entity.getMobType() == net.minecraft.world.entity.MobType.ARTHROPOD) {
            tags.add(DetailTag.of(DetailTag.TRAIT, "arthropod"));
        }
    }
}
