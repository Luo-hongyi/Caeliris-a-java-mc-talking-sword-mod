package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentSensor;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一事件感知器：监听 Forge 游戏事件并写入近期动作缓冲与原始动作归档。
 * <p>
 * 职责：
 * <ol>
 * <li>定时检测群系变化（原有功能）</li>
 * <li>监听战斗事件（攻击、受伤、击杀）</li>
 * <li>监听资源事件（挖矿、使用物品/进食）</li>
 * <li>锚点位移检测（长途旅行）</li>
 * </ol>
 * <p>
 * 所有新增的 {@code @SubscribeEvent} 方法仅做一件事：记录观察到的玩家动作。
 * 不触发 LLM、不创建 Task。
 */
public class TickEventWatcher {

    /** 群系/旅行检测间隔 (tick)，20 tick ≈ 1 秒 */
    private static final int CHECK_INTERVAL_TICKS = 100; // 每 5 秒

    /** 旅行触发距离阈值 (blocks) */
    private static final double TRAVEL_DISTANCE_THRESHOLD = 200.0;

    /** 旅行触发垂直距离阈值 (blocks) */
    private static final double TRAVEL_VERTICAL_THRESHOLD = 50.0;

    /** 玩家伤害归因窗口：在此时间内死亡的目标，视为可归因到玩家主导击杀 */
    private static final long RECENT_PLAYER_DAMAGE_WINDOW_MS = 15_000L;

    /** 采集型 START 防抖窗口：避免同一方块上的重复启动在短时间内连记多次 */
    private static final long GATHER_TOOL_START_DEBOUNCE_MS = 600L;

    private int tickCounter = 0;

    // ── 群系与维度变化检测状态 ──
    private String lastKnownBiome = null;
    private String lastKnownDim = null;
    private long lastBiomeChangeLogTime = 0; // 群系切换的防抖/节流计时戳

    // ── 锚点位移检测状态 ──
    private BlockPos anchorPos = null;

    // ── 战斗归因状态 ──
    private final Map<UUID, Long> recentPlayerDamageByTarget = new LinkedHashMap<>();

    // ── 采集启动防抖状态 ──
    private final Map<String, Long> recentGatherToolStarts = new LinkedHashMap<>();

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

        long now = System.currentTimeMillis();
        pruneRecentPlayerDamage(now);
        pruneRecentGatherToolStarts(now);

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null)
            return;

        // ── 维度变化检测 ──
        checkDimensionChange(player, level);

        // ── 群系变化检测 ──
        if (TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get()) {
            checkBiomeChange(player, level);
        }

        // ── 锚点位移检测（旅行） ──
        checkTravel(player, level);
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

        long now = System.currentTimeMillis();
        pruneRecentPlayerDamage(now);

        LivingEntity hurt = event.getEntity();
        UUID playerUUID = mc.player.getUUID();

        // 玩家攻击别人
        if (event.getSource().getEntity() != null
                && event.getSource().getEntity().getUUID().equals(playerUUID)
                && !hurt.getUUID().equals(playerUUID)) {
            noteRecentPlayerDamage(hurt.getUUID(), now);
            String targetId = entityId(hurt);
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.nativeSpecific(targetId));
            addTraitTags(hurt, tags);
            ActionRecord record = ActionRecord.of(ActionType.ATTACK, targetId, tags);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);
        }
        // 玩家被攻击
        else if (hurt.getUUID().equals(playerUUID)) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.nativeSpecific(sourceId));
            ActionRecord record = ActionRecord.of(ActionType.HURT_BY, sourceId, tags);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);
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

        long now = System.currentTimeMillis();
        pruneRecentPlayerDamage(now);

        LivingEntity dead = event.getEntity();
        UUID playerUUID = mc.player.getUUID();

        // 玩家击杀了目标
        if (!dead.getUUID().equals(playerUUID)
                && wasRecentlyDamagedByPlayer(dead.getUUID(), now)) {
            recentPlayerDamageByTarget.remove(dead.getUUID());
            String targetId = entityId(dead);
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.nativeSpecific(targetId));
            addTraitTags(dead, tags);
            ActionRecord record = ActionRecord.of(ActionType.KILL, targetId, tags);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);
        }
        // 玩家自己死亡
        else if (dead.getUUID().equals(playerUUID)) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            ActionRecord record = ActionRecord.of(ActionType.DEATH, sourceId);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 资源事件
    // ═══════════════════════════════════════════════════════════════

    /**
     * 玩家开始对一个方块进行有效采集。
     */
    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getSide() != LogicalSide.SERVER) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.isAir()) {
            return;
        }
        if (!isEffectiveGatherToolUse(event.getEntity(), stack, state)) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneRecentGatherToolStarts(now);
        String gatherStartKey = gatherToolStartKey(event.getLevel(), event.getPos(), stack);
        if (isGatherToolStartDebounced(gatherStartKey, now)) {
            return;
        }
        noteGatherToolStart(gatherStartKey, now);

        String blockId = blockId(state);
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(blockId));
        addItemTag(stack, tags);
        if (stack.isCorrectToolForDrops(state) || event.getEntity().hasCorrectToolForDrops(state)) {
            tags.add(DetailTag.customGeneric("correct_tool"));
        } else {
            tags.add(DetailTag.customGeneric("effective_tool"));
        }

        recordAction(ActionType.USE_GATHER_TOOL, blockId, tags);
    }

    /**
     * 玩家使用空桶尝试获取流体。
     */
    @SubscribeEvent
    public void onFillBucket(FillBucketEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        ItemStack bucket = event.getEmptyBucket();
        if (bucket.isEmpty()) {
            return;
        }

        HitResult target = event.getTarget();
        if (!(target instanceof BlockHitResult blockHit)) {
            return;
        }

        BlockState state = event.getLevel().getBlockState(blockHit.getBlockPos());
        if (state.isAir()) {
            return;
        }

        String blockId = blockId(state);
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(blockId));
        tags.add(DetailTag.customGeneric("bucket_fill"));
        addItemTag(bucket, tags);

        recordAction(ActionType.USE_GATHER_TOOL, blockId, tags);
    }

    /**
     * 玩家成功钓起物品。
     */
    @SubscribeEvent
    public void onItemFished(ItemFishedEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getEntity() == null || !event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }
        if (event.getDrops().isEmpty()) {
            return;
        }

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.customGeneric("fishing"));
        for (ItemStack drop : event.getDrops()) {
            addItemTag(drop, tags);
        }

        recordAction(ActionType.USE_GATHER_TOOL, "fishing", tags);
    }

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
        tags.add(DetailTag.nativeSpecific(id));

        ActionRecord record = ActionRecord.of(ActionType.MINE_BLOCK, id, tags);
        AgentRuntime.getInstance().getMemory().recordObservedAction(record);
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
        tags.add(DetailTag.nativeSpecific(id));

        ActionRecord record = ActionRecord.of(ActionType.PLACE_BLOCK, id, tags);
        AgentRuntime.getInstance().getMemory().recordObservedAction(record);
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
        tags.add(DetailTag.nativeSpecific(id));

        // 判断是否为食物
        ActionType type = stack.getItem().isEdible() ? ActionType.EAT : ActionType.USE_ITEM;

        ActionRecord record = ActionRecord.of(type, id, tags);
        AgentRuntime.getInstance().getMemory().recordObservedAction(record);
    }

    // ═══════════════════════════════════════════════════════════════
    // 群系变化检测（保留原有逻辑，同时写入近期动作缓冲）
    // ═══════════════════════════════════════════════════════════════

    private void checkBiomeChange(Player player, Level level) {
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

            // 写入近期动作缓冲；同一条记录也会被归档到 ActionArchive
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.customGeneric("biome_change"));
            tags.add(DetailTag.nativeSpecific(currentBiome));
            if (lastKnownDim != null) {
                tags.add(DetailTag.nativeSpecific(lastKnownDim));
            }
            ActionRecord record = ActionRecord.of(ActionType.BIOME_CHANGE, currentBiome, tags);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);

            // 原有的 LLM 触发逻辑（fire BiomeChangeTrigger）
            if (AgentSensor.getInstance().isRuntimeBusy())
                return;
            BiomeChangeTrigger trigger = new BiomeChangeTrigger(oldBiome, currentBiome);
            AgentSensor.getInstance().fireTrigger(trigger, player);
        }
    }

    private void checkDimensionChange(Player player, Level level) {
        String currentDim = level.dimension().location().toString();

        if (lastKnownDim == null) {
            lastKnownDim = currentDim;
            return;
        }

        if (!currentDim.equals(lastKnownDim)) {
            lastKnownDim = currentDim;

            // 写入近期动作缓冲；同一条记录也会被归档到 ActionArchive
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.customGeneric("dim_change"));
            tags.add(DetailTag.nativeSpecific(currentDim));
            if (lastKnownBiome != null) {
                tags.add(DetailTag.nativeSpecific(lastKnownBiome));
            }

            ActionRecord record = ActionRecord.of(ActionType.DIM_CHANGE, currentDim, tags);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);

            // 维度变化时，距离概念已经错乱，重置旅行锚点
            anchorPos = player.blockPosition();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 锚点位移检测（旅行）
    // ═══════════════════════════════════════════════════════════════

    private void checkTravel(Player player, Level level) {
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
            tags.add(DetailTag.customGeneric("travel"));

            // 将当前群系和维度写入标签
            if (lastKnownBiome != null) {
                tags.add(DetailTag.nativeSpecific(lastKnownBiome));
            }
            String dim = player.level().dimension().location().toString();
            tags.add(DetailTag.nativeSpecific(dim));

            ActionRecord record = ActionRecord.of(ActionType.TRAVEL, direction, tags);
            AgentRuntime.getInstance().getMemory().recordObservedAction(record);

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
        if (entity.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER) {
            tags.add(DetailTag.customGeneric("monster"));
        }
        if (entity.isInvertedHealAndHarm()) {
            tags.add(DetailTag.customGeneric("undead"));
        }
        // Forge MobType check for arthropod
        if (entity.getMobType() == net.minecraft.world.entity.MobType.ARTHROPOD) {
            tags.add(DetailTag.customGeneric("arthropod"));
        }
    }

    private boolean isEffectiveGatherToolUse(Player player, ItemStack stack, BlockState state) {
        if (player == null || stack.isEmpty() || state == null || state.isAir()) {
            return false;
        }
        float destroySpeed = stack.getDestroySpeed(state);
        boolean correctTool = stack.isCorrectToolForDrops(state) || player.hasCorrectToolForDrops(state);
        return correctTool || destroySpeed > 1.0f;
    }

    private String blockId(BlockState state) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return blockId != null ? blockId.toString() : "unknown";
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "unknown";
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null ? itemId.toString() : "unknown";
    }

    private void addItemTag(ItemStack stack, Set<DetailTag> tags) {
        if (stack == null || stack.isEmpty() || tags == null) {
            return;
        }
        String itemId = itemId(stack);
        if (!"unknown".equals(itemId)) {
            tags.add(DetailTag.nativeSpecific(itemId));
        }
    }

    private void recordAction(ActionType type, String targetId, Set<DetailTag> tags) {
        ActionRecord record = ActionRecord.of(type, targetId, tags);
        AgentRuntime.getInstance().getMemory().recordObservedAction(record);
    }

    private void noteRecentPlayerDamage(UUID targetId, long now) {
        if (targetId == null) {
            return;
        }
        recentPlayerDamageByTarget.put(targetId, now);
    }

    private boolean wasRecentlyDamagedByPlayer(UUID targetId, long now) {
        if (targetId == null) {
            return false;
        }
        Long lastDamageTime = recentPlayerDamageByTarget.get(targetId);
        if (lastDamageTime == null) {
            return false;
        }
        return now - lastDamageTime <= RECENT_PLAYER_DAMAGE_WINDOW_MS;
    }

    private void pruneRecentPlayerDamage(long now) {
        if (recentPlayerDamageByTarget.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = recentPlayerDamageByTarget.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (now - entry.getValue() > RECENT_PLAYER_DAMAGE_WINDOW_MS) {
                iterator.remove();
            }
        }
    }

    private String gatherToolStartKey(Level level, BlockPos pos, ItemStack stack) {
        String dimension = level != null ? level.dimension().location().toString() : "unknown_dim";
        String itemId = itemId(stack);
        String blockPos = pos != null ? (pos.getX() + ":" + pos.getY() + ":" + pos.getZ()) : "unknown_pos";
        return dimension + "|" + blockPos + "|" + itemId;
    }

    private boolean isGatherToolStartDebounced(String key, long now) {
        if (key == null) {
            return false;
        }
        Long lastTime = recentGatherToolStarts.get(key);
        if (lastTime == null) {
            return false;
        }
        return now - lastTime <= GATHER_TOOL_START_DEBOUNCE_MS;
    }

    private void noteGatherToolStart(String key, long now) {
        if (key == null) {
            return;
        }
        recentGatherToolStarts.put(key, now);
    }

    private void pruneRecentGatherToolStarts(long now) {
        if (recentGatherToolStarts.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Long>> iterator = recentGatherToolStarts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > GATHER_TOOL_START_DEBOUNCE_MS) {
                iterator.remove();
            }
        }
    }

}
