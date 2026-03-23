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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** 功能方块交互候选窗口：先看到右键候选，再等待容器真正打开 */
    private static final long FUNCTION_BLOCK_INTERACTION_WINDOW_MS = 1_500L;

    /** NPC 交互短窗口防重：避免同一次右键在多个实体交互事件中重复入档 */
    private static final long NPC_INTERACTION_DEBOUNCE_MS = 800L;

    /** 发呆动作阈值：超过该时长没有任何非 IDLE 动作，则补写一条 IDLE。 */
    private static final long IDLE_ACTION_THRESHOLD_MS = 120_000L;

    private int tickCounter = 0;

    // ── 群系与维度变化检测状态 ──
    private String lastKnownBiome = null;
    private String lastKnownDim = null;
    private long lastBiomeChangeLogTime = 0; // 群系切换的防抖/节流计时戳

    // ── 锚点位移检测状态 ──
    private BlockPos anchorPos = null;

    // ── 战斗归因状态 ──
    private final Map<UUID, Long> recentPlayerDamageByTarget = new LinkedHashMap<>();

    // ── 功能方块交互候选状态 ──
    private PendingFunctionBlockInteraction pendingFunctionBlockInteraction = null;

    // ── NPC 交互防重状态 ──
    private final Map<UUID, Long> recentNpcInteractions = new LinkedHashMap<>();

    // ── 发呆检测状态 ──
    private long lastNonIdleActionTimestampMs = -1L;
    private long lastIdleActionTimestampMs = -1L;

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
        pruneRecentNpcInteractions(now);

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null)
            return;
        if (lastNonIdleActionTimestampMs < 0L) {
            lastNonIdleActionTimestampMs = now;
        }

        // ── 维度变化检测 ──
        checkDimensionChange(player, level);

        // ── 群系变化检测 ──
        if (TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get()) {
            checkBiomeChange(player, level);
        }

        // ── 锚点位移检测（旅行） ──
        checkTravel(player, level);

        // ── 发呆检测 ──
        checkIdle(now);
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
            emitObservedAction(ActionRecord.of(ActionType.ATTACK, targetId, tags));
        }
        // 玩家被攻击
        else if (hurt.getUUID().equals(playerUUID)) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            Set<DetailTag> tags = new LinkedHashSet<>();
            tags.add(DetailTag.nativeSpecific(sourceId));
            emitObservedAction(ActionRecord.of(ActionType.HURT_BY, sourceId, tags));
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
            emitObservedAction(ActionRecord.of(ActionType.KILL, targetId, tags));
        }
        // 玩家自己死亡
        else if (dead.getUUID().equals(playerUUID)) {
            String sourceId = event.getSource().getEntity() != null
                    ? entityId((LivingEntity) event.getSource().getEntity())
                    : "unknown";
            emitObservedAction(ActionRecord.of(ActionType.DEATH, sourceId));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 资源事件
    // ═══════════════════════════════════════════════════════════════

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
     * 玩家右键一个可能的功能方块时，先登记一个短时候选；
     * 只有后续真正打开了对应容器，才记录 INTERACT_FUNCTION_BLOCK。
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        prunePendingFunctionBlockInteraction(System.currentTimeMillis());

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!isFunctionBlockCandidate(event.getLevel(), event.getPos(), state)) {
            return;
        }

        String id = blockId(state);
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(id));
        tags.add(DetailTag.customGeneric("function_block"));

        pendingFunctionBlockInteraction = new PendingFunctionBlockInteraction(id, tags, System.currentTimeMillis());
    }

    /**
     * 容器真正打开后，回填成功的功能方块交互记录，避免把“点到了但没打开”记成生产行为。
     */
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        long now = System.currentTimeMillis();
        prunePendingFunctionBlockInteraction(now);
        if (pendingFunctionBlockInteraction == null) {
            return;
        }
        if (!isFunctionBlockMenu(event.getContainer())) {
            pendingFunctionBlockInteraction = null;
            return;
        }

        recordAction(
                ActionType.INTERACT_FUNCTION_BLOCK,
                pendingFunctionBlockInteraction.blockId(),
                pendingFunctionBlockInteraction.tags());
        pendingFunctionBlockInteraction = null;
    }

    /**
     * 玩家完成一次 crafting 产出。
     */
    @SubscribeEvent
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        ItemStack output = event.getCrafting();
        if (output == null || output.isEmpty()) {
            return;
        }

        Set<DetailTag> tags = new LinkedHashSet<>();
        addItemTag(output, tags);
        tags.add(DetailTag.customGeneric("crafted_output"));
        tags.add(DetailTag.customGeneric("crafting_recipe"));

        recordAction(ActionType.CRAFT_ITEM, itemId(output), tags);
    }

    /**
     * 玩家完成一次 furnace-family smelting / blasting / smoking 产出。
     */
    @SubscribeEvent
    public void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        ItemStack output = event.getSmelting();
        if (output == null || output.isEmpty()) {
            return;
        }

        Set<DetailTag> tags = new LinkedHashSet<>();
        addItemTag(output, tags);
        tags.add(DetailTag.customGeneric("crafted_output"));
        tags.add(DetailTag.customGeneric("smelted_output"));

        recordAction(ActionType.CRAFT_ITEM, itemId(output), tags);
    }

    /**
     * 玩家真正拾取了地面物品。
     */
    @SubscribeEvent
    public void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        ItemStack stack = event.getStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }

        recordAcquireItem(stack, "picked_up_item");
    }

    /**
     * 玩家与 NPC 的精确部位交互。
     */
    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        recordNpcInteraction(event.getEntity(), event.getHand(), event.getTarget());
    }

    /**
     * 玩家与 NPC 的一般交互。
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        recordNpcInteraction(event.getEntity(), event.getHand(), event.getTarget());
    }

    /**
     * 玩家真正完成了一笔村民交易。
     */
    @SubscribeEvent
    public void onTradeWithVillager(TradeWithVillagerEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().getUUID().equals(mc.player.getUUID())) {
            return;
        }

        AbstractVillager villager = event.getAbstractVillager();
        String villagerId = entityId(villager);
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(villagerId));
        tags.add(DetailTag.customGeneric("trade_completed"));
        tags.add(DetailTag.customGeneric("merchant_trade"));
        addNpcTags(villager, tags);
        addItemTag(event.getMerchantOffer().getResult(), tags);

        recordAction(ActionType.TRADE, villagerId, tags);
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
        if (!(event.getLevel() instanceof ServerLevel serverLevel))
            return;
        if (!event.getPlayer().getUUID().equals(mc.player.getUUID()))
            return;

        BlockState state = event.getState();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String id = blockId != null ? blockId.toString() : "unknown";

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(id));

        emitObservedAction(ActionRecord.of(ActionType.MINE_BLOCK, id, tags));

        ItemStack stack = event.getPlayer().getMainHandItem();
        if (stack.isEmpty()) {
            return;
        }
        if (!isEffectiveGatherToolUse(event.getPlayer(), stack, state)) {
            return;
        }
        if (!hasHarvestDrops(serverLevel, event.getPos(), state, event.getPlayer(), stack)) {
            return;
        }

        Set<DetailTag> gatherTags = new LinkedHashSet<>();
        gatherTags.add(DetailTag.nativeSpecific(id));
        addItemTag(stack, gatherTags);
        gatherTags.add(DetailTag.customGeneric("harvest_drop"));
        if (stack.isCorrectToolForDrops(state) || event.getPlayer().hasCorrectToolForDrops(state)) {
            gatherTags.add(DetailTag.customGeneric("correct_tool"));
        } else {
            gatherTags.add(DetailTag.customGeneric("effective_tool"));
        }

        recordAction(ActionType.USE_GATHER_TOOL, id, gatherTags);
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

        emitObservedAction(ActionRecord.of(ActionType.PLACE_BLOCK, id, tags));
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

        emitObservedAction(ActionRecord.of(type, id, tags));
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
            emitObservedAction(ActionRecord.of(ActionType.BIOME_CHANGE, currentBiome, tags));

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

            emitObservedAction(ActionRecord.of(ActionType.DIM_CHANGE, currentDim, tags));

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

            emitObservedAction(ActionRecord.of(ActionType.TRAVEL, direction, tags));

            // 重置锚点
            anchorPos = currentPos;
        }
    }

    private void checkIdle(long now) {
        if (lastNonIdleActionTimestampMs < 0L) {
            lastNonIdleActionTimestampMs = now;
            return;
        }
        if (now - lastNonIdleActionTimestampMs < IDLE_ACTION_THRESHOLD_MS) {
            return;
        }
        if (lastIdleActionTimestampMs >= 0L && now - lastIdleActionTimestampMs < IDLE_ACTION_THRESHOLD_MS) {
            return;
        }

        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.customGeneric("idle"));
        emitObservedAction(ActionRecord.of(ActionType.IDLE, "idle", tags));
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

    private void recordNpcInteraction(Player player, InteractionHand hand, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || player == null || target == null) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!player.getUUID().equals(mc.player.getUUID())) {
            return;
        }
        if (!isNpcTarget(target)) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneRecentNpcInteractions(now);
        if (isNpcInteractionDebounced(target.getUUID(), now)) {
            return;
        }
        noteNpcInteraction(target.getUUID(), now);

        String npcId = entityId(target);
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(npcId));
        tags.add(DetailTag.customGeneric("npc_interaction"));
        addNpcTags(target, tags);

        recordAction(ActionType.INTERACT_NPC, npcId, tags);
    }

    private boolean hasHarvestDrops(ServerLevel level, BlockPos pos, BlockState state, Player player, ItemStack stack) {
        if (level == null || pos == null || state == null || state.isAir()) {
            return false;
        }

        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, stack);
        if (drops.isEmpty()) {
            return false;
        }

        for (ItemStack drop : drops) {
            if (drop != null && !drop.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isNpcTarget(Entity target) {
        return target instanceof Npc;
    }

    private boolean isFunctionBlockCandidate(Level level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null || state.isAir()) {
            return false;
        }

        String blockId = blockId(state);
        if ("unknown".equals(blockId)) {
            return false;
        }
        if (isStorageLikeId(blockId)) {
            return false;
        }

        return state.getMenuProvider(level, pos) != null;
    }

    private boolean isFunctionBlockMenu(AbstractContainerMenu container) {
        if (container == null || container.getType() == null) {
            return false;
        }

        ResourceLocation menuTypeId = ForgeRegistries.MENU_TYPES.getKey(container.getType());
        if (menuTypeId == null) {
            return true;
        }

        String path = menuTypeId.getPath();
        if (path == null || path.isBlank()) {
            return true;
        }

        return !isStorageLikePath(path) && !"merchant".equals(path) && !"inventory".equals(path);
    }

    private boolean isStorageLikeId(String registryId) {
        if (registryId == null || registryId.isBlank()) {
            return false;
        }
        int separator = registryId.indexOf(':');
        String path = separator >= 0 ? registryId.substring(separator + 1) : registryId;
        return isStorageLikePath(path);
    }

    private boolean isStorageLikePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.contains("chest")
                || path.contains("barrel")
                || path.contains("shulker")
                || path.contains("hopper")
                || path.contains("dispenser")
                || path.contains("dropper")
                || path.contains("crate")
                || path.contains("drawer")
                || path.contains("cabinet")
                || path.contains("locker")
                || path.contains("bin");
    }

    private String blockId(BlockState state) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return blockId != null ? blockId.toString() : "unknown";
    }

    private String entityId(Entity entity) {
        if (entity == null) {
            return "unknown";
        }
        ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return rl != null ? rl.toString() : "unknown";
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

    private void addNpcTags(Entity entity, Set<DetailTag> tags) {
        if (entity == null || tags == null) {
            return;
        }

        tags.add(DetailTag.customGeneric("npc"));
        if (entity instanceof AbstractVillager) {
            tags.add(DetailTag.customGeneric("merchant_npc"));
        }
        if (entity instanceof Villager villager) {
            ResourceLocation professionId = BuiltInRegistries.VILLAGER_PROFESSION
                    .getKey(villager.getVillagerData().getProfession());
            if (professionId != null) {
                tags.add(DetailTag.customSpecific("villager_profession/" + professionId));
            }
        }
    }

    private void recordAcquireItem(ItemStack stack, String... sourceTags) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        Set<DetailTag> tags = new LinkedHashSet<>();
        addItemTag(stack, tags);
        tags.add(DetailTag.customGeneric("acquired_item"));
        if (sourceTags != null) {
            for (String sourceTag : sourceTags) {
                if (sourceTag != null && !sourceTag.isBlank()) {
                    tags.add(DetailTag.customGeneric(sourceTag));
                }
            }
        }

        recordAction(ActionType.ACQUIRE_ITEM, itemId(stack), tags);
    }

    private void recordAction(ActionType type, String targetId, Set<DetailTag> tags) {
        emitObservedAction(ActionRecord.of(type, targetId, tags));
    }

    private void emitObservedAction(ActionRecord record) {
        if (record == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (record.getType() == ActionType.IDLE) {
            lastIdleActionTimestampMs = now;
        } else {
            lastNonIdleActionTimestampMs = now;
        }
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

    private boolean isNpcInteractionDebounced(UUID targetId, long now) {
        if (targetId == null) {
            return false;
        }
        Long lastTime = recentNpcInteractions.get(targetId);
        return lastTime != null && now - lastTime <= NPC_INTERACTION_DEBOUNCE_MS;
    }

    private void noteNpcInteraction(UUID targetId, long now) {
        if (targetId == null) {
            return;
        }
        recentNpcInteractions.put(targetId, now);
    }

    private void pruneRecentNpcInteractions(long now) {
        if (recentNpcInteractions.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = recentNpcInteractions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (now - entry.getValue() > NPC_INTERACTION_DEBOUNCE_MS) {
                iterator.remove();
            }
        }
    }

    private void prunePendingFunctionBlockInteraction(long now) {
        if (pendingFunctionBlockInteraction == null) {
            return;
        }
        if (now - pendingFunctionBlockInteraction.timestampMs() > FUNCTION_BLOCK_INTERACTION_WINDOW_MS) {
            pendingFunctionBlockInteraction = null;
        }
    }

    private record PendingFunctionBlockInteraction(String blockId, Set<DetailTag> tags, long timestampMs) {
        private PendingFunctionBlockInteraction {
            tags = tags == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        }
    }

}
