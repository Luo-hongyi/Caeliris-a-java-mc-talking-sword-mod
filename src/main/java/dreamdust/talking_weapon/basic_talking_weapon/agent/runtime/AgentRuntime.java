package dreamdust.talking_weapon.basic_talking_weapon.agent.runtime;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentStateStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.concurrent.PriorityBlockingQueue;

/**
 * 常驻 Agent Runtime：双队列 + 双 worker 线程架构。
 * <p>
 * <b>前台队列 (Foreground)</b>：处理玩家主动触发的交互事件（攻击、右键、受伤等）。
 * 优先响应，不允许被后台抢占。
 * <p>
 * <b>后台队列 (Background)</b>：处理 Agent 内部的异步任务（碎碎念、记忆沉淀、
 * 持有者画像更新、内心活动等）。后台 worker 完全独立，不阻塞前台交互。
 * <p>
 * 两个队列共享同一份 {@link AgentMemory} 和 {@link AgentState}（注意：任务内部
 * 对共享状态的写操作需保证线程安全，当前以字段级 volatile 为基础防护）。
 */
public final class AgentRuntime {

    private static final AgentRuntime INSTANCE = new AgentRuntime();

    // ── 前台（Foreground）──
    private final PriorityBlockingQueue<AgentTask> fgQueue = new PriorityBlockingQueue<>();
    private final Thread fgWorker;
    private volatile boolean fgBusy = false;
    private volatile AgentTask fgCurrentTask;

    // ── 后台（Background）──
    private final PriorityBlockingQueue<AgentTask> bgQueue = new PriorityBlockingQueue<>();
    private final Thread bgWorker;
    private volatile boolean bgBusy = false;
    private volatile AgentTask bgCurrentTask;

    private volatile boolean running = true;

    /** 全局共享记忆（两个 worker 线程均可读写） */
    private final AgentMemory memory = new AgentMemory();

    /** 全局共享运行时状态（高频，两个 worker 线程均可读写） */
    private final AgentState state = new AgentState();

    /** 全局共享人格画像（低频，仅在 ReflectionTask 中更新） */
    private final AgentPersona persona = new AgentPersona();

    private AgentRuntime() {
        AgentStateStorage.loadIntoRuntime(state, persona);

        this.fgWorker = new Thread(this::fgLoop, "TalkingWeapon-FG");
        this.fgWorker.setDaemon(true);
        this.fgWorker.start();

        this.bgWorker = new Thread(this::bgLoop, "TalkingWeapon-BG");
        this.bgWorker.setDaemon(true);
        this.bgWorker.start();
    }

    public static AgentRuntime getInstance() {
        return INSTANCE;
    }

    // ═══════════════════════════════════════════════════════════════
    // 前台队列 API（供 AgentSensor / IAgentTrigger 使用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 提交一个前台任务（玩家交互触发）。
     *
     * @return true 若成功入列
     */
    public boolean submit(AgentTask task) {
        if (!running)
            return false;
        return fgQueue.offer(task);
    }

    /**
     * 取消当前前台任务并清空前台队列（玩家左键打断时调用）。
     */
    public void cancelCurrentAndClearQueue() {
        fgQueue.clear();
        AgentTask t = fgCurrentTask;
        if (t != null)
            t.cancel();
        fgWorker.interrupt();
    }

    /**
     * 前台是否繁忙（队列非空或正在执行）。
     */
    public boolean isBusy() {
        return fgBusy || !fgQueue.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 后台队列 API（供后台异步任务使用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 提交一个后台任务（碎碎念 / 记忆沉淀 / 内心活动等）。
     *
     * @return true 若成功入列
     */
    public boolean submitBackground(AgentTask task) {
        if (!running)
            return false;
        return bgQueue.offer(task);
    }

    /**
     * 取消当前后台任务并清空后台队列。
     */
    public void cancelBackgroundAndClearQueue() {
        bgQueue.clear();
        AgentTask t = bgCurrentTask;
        if (t != null)
            t.cancel();
        bgWorker.interrupt();
    }

    /**
     * 后台是否繁忙（队列非空或正在执行）。
     */
    public boolean isBackgroundBusy() {
        return bgBusy || !bgQueue.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 共享状态访问
    // ═══════════════════════════════════════════════════════════════

    public AgentMemory getMemory() {
        return memory;
    }

    public AgentState getState() {
        return state;
    }

    public AgentPersona getPersona() {
        return persona;
    }

    // ═══════════════════════════════════════════════════════════════
    // Worker 循环
    // ═══════════════════════════════════════════════════════════════

    /** 前台 worker 循环 */
    private void fgLoop() {
        while (running) {
            AgentTask task;
            try {
                task = fgQueue.take();
            } catch (InterruptedException e) {
                Thread.interrupted();
                continue;
            }
            fgCurrentTask = task;
            fgBusy = true;
            try {
                if (!task.isCancelled())
                    task.execute();
            } catch (Throwable t) {
                System.err.println(
                        "[TalkingWeapon-FG] Task threw exception: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace();
            } finally {
                fgCurrentTask = null;
                fgBusy = false;
                Thread.interrupted();
            }
        }
    }

    /** 后台 worker 循环 */
    private void bgLoop() {
        while (running) {
            AgentTask task;
            try {
                task = bgQueue.take();
            } catch (InterruptedException e) {
                Thread.interrupted();
                continue;
            }
            bgCurrentTask = task;
            bgBusy = true;
            try {
                if (!task.isCancelled())
                    task.execute();
            } catch (Throwable t) {
                System.err.println(
                        "[TalkingWeapon-BG] Task threw exception: " + t.getClass().getName() + ": " + t.getMessage());
                t.printStackTrace();
            } finally {
                bgCurrentTask = null;
                bgBusy = false;
                Thread.interrupted();
            }
        }
    }
}
