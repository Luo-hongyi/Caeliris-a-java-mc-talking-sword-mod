package dreamdust.talking_weapon.basic_talking_weapon.agent.runtime;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.concurrent.PriorityBlockingQueue;

/**
 * 常驻 Agent Runtime：
 * 单 worker 线程 + 优先级任务队列，负责串行执行 {@link AgentTask}。
 * 同时持有全局共享的 {@link AgentMemory} 和 {@link AgentState}。
 */
public final class AgentRuntime {

    private static final AgentRuntime INSTANCE = new AgentRuntime();

    private final PriorityBlockingQueue<AgentTask> queue = new PriorityBlockingQueue<>();
    private final Thread worker;
    private volatile boolean running = true;
    private volatile boolean busy = false;
    private volatile AgentTask currentTask;

    /** 全局共享记忆 */
    private final AgentMemory memory = new AgentMemory();

    /** 全局共享状态 */
    private final AgentState state = new AgentState();

    private AgentRuntime() {
        this.worker = new Thread(this::runLoop, "TalkingWeapon-AgentRuntime");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public static AgentRuntime getInstance() {
        return INSTANCE;
    }

    /**
     * 提交一个 AgentTask 到优先级队列，由 worker 线程串行执行。
     */
    public boolean submit(AgentTask task) {
        if (!running) return false;
        return queue.offer(task);
    }

    /**
     * 取消当前正在执行的任务并清空队列。
     */
    public void cancelCurrentAndClearQueue() {
        queue.clear();
        AgentTask t = currentTask;
        if (t != null) t.cancel();
        worker.interrupt();
    }

    public boolean isBusy() {
        return busy || !queue.isEmpty();
    }

    public AgentMemory getMemory() { return memory; }
    public AgentState getState() { return state; }

    private void runLoop() {
        while (running) {
            AgentTask task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                Thread.interrupted();
                continue;
            }

            currentTask = task;
            busy = true;
            try {
                if (!task.isCancelled()) {
                    task.execute();
                }
            } catch (Throwable ignored) {
            } finally {
                currentTask = null;
                busy = false;
                Thread.interrupted();
            }
        }
    }
}
