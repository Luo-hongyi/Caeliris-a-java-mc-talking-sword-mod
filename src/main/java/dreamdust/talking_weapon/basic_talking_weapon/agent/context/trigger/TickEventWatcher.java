package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentSensor;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Tick 事件监听器：通过 Forge 客户端 Tick 事件实现主动触发。
 * <p>
 * 每隔固定 tick 检测游戏状态差异（如群系变化），
 * 并通过 {@link AgentSensor#fireTrigger(IAgentTrigger, Player)} 自动派发任务。
 * <p>
 * 注册方式：在 {@code basic_talking_weapon} 构造函数中
 * {@code MinecraftForge.EVENT_BUS.register(new TickEventWatcher())}。
 */
public class TickEventWatcher {

    /** 检测间隔 (tick)，20 tick ≈ 1 秒 */
    private static final int CHECK_INTERVAL_TICKS = 100; // 每 5 秒检测一次

    /** 内部 tick 计数器 */
    private int tickCounter = 0;

    /** 上次已知生物群系 */
    private String lastKnownBiome = null;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // 只在 Phase.END 处理，避免重复
        if (event.phase != TickEvent.Phase.END) return;

        // 主动触发器总开关
        if (!TalkingWeaponConfig.ENABLE_PROACTIVE_TRIGGERS.get()) return;

        tickCounter++;
        if (tickCounter < CHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        // ── 群系变化检测 ──────────────────────────────────
        if (TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get()) {
            checkBiomeChange(player, level);
        }
    }

    /**
     * 检测玩家当前群系是否发生变化，如果变了则自动派发一个 BiomeChangeTrigger。
     */
    private void checkBiomeChange(Player player, Level level) {
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

            if (AgentSensor.getInstance().isRuntimeBusy()) return;

            BiomeChangeTrigger trigger = new BiomeChangeTrigger(oldBiome, currentBiome);
            AgentSensor.getInstance().fireTrigger(trigger, player);
        }
    }
}
