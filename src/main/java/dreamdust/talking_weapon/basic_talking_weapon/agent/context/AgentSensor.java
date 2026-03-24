package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.IAgentTrigger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Agent 感知器：所有游戏事件的唯一入口。
 * <p>
 * 接收 {@link IAgentTrigger} 实例，按其声明的 {@link IContextCollector} 列表
 * 在主线程上采集 AgentContext 快照，并提交对应的 AgentTask 到 AgentRuntime。
 * 提供防抖机制，避免短时间内重复触发。
 */
public class AgentSensor {

    private static final AgentSensor INSTANCE = new AgentSensor();

    /** 两次触发之间的最小间隔 (毫秒) */
    private long debounceCooldownMs = 2000L;

    /** 上次触发的时间戳 */
    private long lastTriggerTime = 0L;

    private AgentSensor() {
    }

    public static AgentSensor getInstance() {
        return INSTANCE;
    }

    // ═══════════════════════════════════════════════════════════════
    // 通用触发入口 —— 所有 IAgentTrigger 通过这里提交
    // ═══════════════════════════════════════════════════════════════

    /**
     * 通用触发入口（主线程调用）。
     * <p>
     * 流程：防抖检查 → 忙检查 → 执行 collectors → trigger.createTask → submit
     *
     * @param trigger IAgentTrigger 实例
     * @param player  当前玩家（用于 ActionBar 反馈，可为 null 则静默）
     * @return true 表示已成功提交任务
     */
    public boolean fireTrigger(IAgentTrigger trigger, Player player) {
        AgentRuntime runtime = AgentRuntime.getInstance();

        // 忙检查
        if (runtime.isBusy()) {
            if (player != null) {
                showActionBar(player, PromptRegistry.getInstance().getBusySpeech());
            }
            return false;
        }

        // 防抖检查
        if (trigger.requiresDebounce() && !canTrigger()) {
            return false;
        }

        if (trigger.requiresDebounce()) {
            markTriggered();
        }

        // 0. 开始日志会话
        AgentLogger.beginSession(trigger.getSourceName(), trigger.getUserMessage(), trigger.getEventInstruction());

        // 当前在 right_click fire 时额外写出一份独立的动作日志，
        // 其中包含：
        // 1. 原始 ActionArchive 时间线
        // 2. fixed 60 秒 compaction
        // 3. moving segment 及其 local intent
        // 便于同时观察分析层与 prompt 近期感知层。
        if ("right_click".equals(trigger.getSourceName())) {
            AgentLogger.logActionArchiveDump(runtime.getMemory());
        }

        // 1. 创建 Builder，设置基础元信息
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        Level level = mc.level;

        AgentContext.Builder builder = AgentContextCollectionRunner.collectIntoBuilder(
                trigger.getSourceName(),
                trigger.getCollectors(),
                p,
                level);

        // 3. 将触发器的两段式 prompt 写入 extras
        builder.extra("user_message", trigger.getUserMessage());
        builder.extra("event_instruction", trigger.getEventInstruction());
        builder.extra("enabled_actions", String.join(",", trigger.getEnabledActions()));

        AgentContext context = builder.build();

        // 2.5. 记录 Collector 采集结果
        AgentLogger.logContext(context);

        // 3. 显示触发提示（仅对需要防抖的交互式触发器）
        if (trigger.requiresDebounce() && player != null) {
            String triggerSpeech = PromptRegistry.getInstance().getTriggerSpeech();
            showActionBar(player, triggerSpeech);
        }

        // 4. 创建任务并提交
        AgentTask task = trigger.createTask(context);
        if (task == null)
            return false;

        boolean accepted = runtime.submit(task);
        if (!accepted) {
            AgentLogger.logError("任务提交失败：运行时不可用", null);
            AgentLogger.endSession();
            if (player != null) {
                showActionBar(player, "剑：运行时不可用。请稍后再试。");
            }
        }

        return accepted;
    }

    // ═══════════════════════════════════════════════════════════════
    // 取消
    // ═══════════════════════════════════════════════════════════════

    /**
     * 左键打断当前任务（主线程调用）。
     *
     * @param player 当前玩家
     */
    public void triggerCancel(Player player) {
        AgentRuntime runtime = AgentRuntime.getInstance();
        if (!runtime.isBusy())
            return;

        runtime.cancelCurrentAndClearQueue();
        String cancelSpeech = PromptRegistry.getInstance().getCancelSpeech();
        showActionBar(player, cancelSpeech);
    }

    // ═══════════════════════════════════════════════════════════════
    // 状态查询
    // ═══════════════════════════════════════════════════════════════

    /** 检查是否已过冷却期，可以再次触发 */
    public boolean canTrigger() {
        return System.currentTimeMillis() - lastTriggerTime >= debounceCooldownMs;
    }

    /** 标记一次触发，更新时间戳 */
    public void markTriggered() {
        this.lastTriggerTime = System.currentTimeMillis();
    }

    /** 检查 AgentRuntime 是否正在执行任务 */
    public boolean isRuntimeBusy() {
        return AgentRuntime.getInstance().isBusy();
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    /** 在 ActionBar 上显示一行文字（主线程安全） */
    private void showActionBar(Player player, String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (player != null) {
                player.displayClientMessage(Component.literal(text), true);
            }
        });
    }

    // ---- Configuration ----

    public long getDebounceCooldownMs() {
        return debounceCooldownMs;
    }

    public void setDebounceCooldownMs(long debounceCooldownMs) {
        this.debounceCooldownMs = debounceCooldownMs;
    }
}
