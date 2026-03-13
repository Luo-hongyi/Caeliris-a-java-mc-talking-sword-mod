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
import java.util.UUID;

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

    // ── 群系与维度变化检测状态 ──
    private String lastKnownBiome = null;
    private String lastKnownDim = null;
    private long lastBiomeChangeLogTime = 0; // 群系切换的防抖/节流计时戳

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

        // ── 维度变化检测 ──
        checkDimensionChange(player, level, buffer);

        // ── 群系变化检测 ──
        if (TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get()) {
            checkBiomeChange(player, level, buffer);
        }

        // ── 锚点位移检测（旅行） ──
        checkTravel(player, level, buffer);
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
        UUID playerUUID = mc.player.getUUID();

        // 玩家攻击别人
        if (event.getSource().getEntity() != null
                && event.getSource().getEntity().getUUID().equals(playerUUID)) {
            String targetId = entityId(hurt);
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ENTITY, targetId));
            addTraitTags(hurt, tags);
            ActionRecord record = ActionRecord.of(ActionType.ATTACK, targetId, tags);
            AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
            logBufferAdd(record);
        }
        // 玩家被攻击
        else if (hurt.getUUID().equals(playerUUID)) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ENTITY, sourceId));
            ActionRecord record = ActionRecord.of(ActionType.HURT_BY, sourceId, tags);
            AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
            logBufferAdd(record);
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
        UUID playerUUID = mc.player.getUUID();

        // 玩家击杀了目标
        if (event.getSource().getEntity() != null
                && event.getSource().getEntity().getUUID().equals(playerUUID)
                && !dead.getUUID().equals(playerUUID)) {
            String targetId = entityId(dead);
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ENTITY, targetId));
            addTraitTags(dead, tags);
            ActionRecord record = ActionRecord.of(ActionType.KILL, targetId, tags);
            AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
            logBufferAdd(record);
        }
        // 玩家自己死亡
        else if (dead.getUUID().equals(playerUUID)) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            ActionRecord record = ActionRecord.of(ActionType.DEATH, sourceId);
            AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
            logBufferAdd(record);
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
        if (event.getPlayer() == null)
            return;
        if (!event.getPlayer().getUUID().equals(mc.player.getUUID()))
            return;

        BlockState state = event.getState();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String id = blockId != null ? blockId.toString() : "unknown";

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.of(DetailTag.BLOCK, id));

        ActionRecord record = ActionRecord.of(ActionType.MINE_BLOCK, id, tags);
        AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
        logBufferAdd(record);
    }

    /**
     * 玩家放置方块。
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        if (event.getEntity() == null)
            return;
        if (!event.getEntity().getUUID().equals(mc.player.getUUID()))
            return;

        BlockState state = event.getPlacedBlock();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String id = blockId != null ? blockId.toString() : "unknown";

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.of(DetailTag.BLOCK, id));

        ActionRecord record = ActionRecord.of(ActionType.PLACE_BLOCK, id, tags);
        AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
        logBufferAdd(record);
    }

    /**
     * 玩家使用物品完毕（进食/喝药水等）。
     */
    @SubscribeEvent
    public void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        if (event.getEntity() == null)
            return;
        if (!event.getEntity().getUUID().equals(mc.player.getUUID()))
            return;

        ItemStack stack = event.getItem();
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String id = itemId != null ? itemId.toString() : "unknown";

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.of(DetailTag.ITEM, id));

        // 判断是否为食物
        ActionType type = stack.getItem().isEdible() ? ActionType.EAT : ActionType.USE_ITEM;

        ActionRecord record = ActionRecord.of(type, id, tags);
        AgentRuntime.getInstance().getMemory().getActionBuffer().add(record);
        logBufferAdd(record);
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
            lastBiomeChangeLogTime = System.currentTimeMillis();
            return;
        }

        if (!currentBiome.equals(lastKnownBiome)) {
            long now = System.currentTimeMillis();
            // 平滑防抖（3分钟）：距离上次记录群系不足3分钟时，暂不处理状态更新和记录，
            // 避免玩家在边界反复横跳导致垃圾记录刷屏
            if (now - lastBiomeChangeLogTime < 180_000L) {
                return;
            }

            String oldBiome = lastKnownBiome;
            lastKnownBiome = currentBiome;
            lastBiomeChangeLogTime = now;

            // 写入 ActionBuffer
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ACTION, "biome_change"));
            tags.add(DetailTag.of(DetailTag.BIOME, currentBiome));
            if (lastKnownDim != null) {
                tags.add(DetailTag.of(DetailTag.DIM, lastKnownDim));
            }
            ActionRecord record = ActionRecord.of(ActionType.BIOME_CHANGE, currentBiome, tags);
            buffer.add(record);
            logBufferAdd(record);

            // 原有的 LLM 触发逻辑（fire BiomeChangeTrigger）
            if (AgentSensor.getInstance().isRuntimeBusy())
                return;
            BiomeChangeTrigger trigger = new BiomeChangeTrigger(oldBiome, currentBiome);
            AgentSensor.getInstance().fireTrigger(trigger, player);
        }
    }

    private void checkDimensionChange(Player player, Level level, ActionBuffer buffer) {
        String currentDim = level.dimension().location().toString();

        if (lastKnownDim == null) {
            lastKnownDim = currentDim;
            return;
        }

        if (!currentDim.equals(lastKnownDim)) {
            lastKnownDim = currentDim;

            // 写入 ActionBuffer
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.of(DetailTag.ACTION, "dim_change"));
            tags.add(DetailTag.of(DetailTag.DIM, currentDim));
            if (lastKnownBiome != null) {
                tags.add(DetailTag.of(DetailTag.BIOME, lastKnownBiome));
            }

            ActionRecord record = ActionRecord.of(ActionType.DIM_CHANGE, currentDim, tags);
            buffer.add(record);
            logBufferAdd(record);

            // 维度变化时，距离概念已经错乱，重置旅行锚点
            anchorPos = player.blockPosition();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 锚点位移检测（旅行）
    // ═══════════════════════════════════════════════════════════════

    private void checkTravel(Player player, Level level, ActionBuffer buffer) {
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
            tags.add(DetailTag.of(DetailTag.ACTION, "travel"));

            // 将当前群系和维度写入标签
            if (lastKnownBiome != null) {
                tags.add(DetailTag.of(DetailTag.BIOME, lastKnownBiome));
            }
            String dim = player.level().dimension().location().toString();
            tags.add(DetailTag.of(DetailTag.DIM, dim));

            ActionRecord record = ActionRecord.of(ActionType.TRAVEL, direction, tags);
            buffer.add(record);
            logBufferAdd(record);

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

    // ═══════════════════════════════════════════════════════════════
    // ActionBuffer 调试日志
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将每次写入 ActionBuffer 的记录打印到 stdout，方便调试。
     */
    private void logBufferAdd(ActionRecord record) {
        ActionBuffer buffer = AgentRuntime.getInstance().getMemory().getActionBuffer();
        System.out.println("[ActionBuffer +] " + record.getType()
                + " target=" + record.getTarget()
                + " count=" + record.getCount()
                + " tags=" + record.getDetailTags()
                + " | buffer_size=" + buffer.size());
    }
}
