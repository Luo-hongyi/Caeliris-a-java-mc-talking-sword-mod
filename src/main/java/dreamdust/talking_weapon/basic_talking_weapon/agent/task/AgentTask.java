package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 任务基类：所有后台任务的抽象父类。
 * <p>
 * 任务携带一个不可变的 AgentContext 快照，在 AgentRuntime 的 worker 线程上串行执行。
 * 子类通过实现 {@link #execute()} 定义具体逻辑。
 */
public abstract class AgentTask implements Comparable<AgentTask> {

    private static final AtomicLong NEXT_ORDER = new AtomicLong(0L);

    /** 任务唯一标识 */
    private final String taskId;

    /** 任务优先级 (数字越小越优先) */
    private final int priority;

    /** 同优先级任务的稳定顺序号。 */
    private final long order;

    /** 任务创建时的上下文快照 */
    private final AgentContext context;

    /** 取消标志 */
    private volatile boolean cancelled = false;

    protected AgentTask(int priority, AgentContext context) {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.priority = priority;
        this.order = NEXT_ORDER.getAndIncrement();
        this.context = context;
    }

    /**
     * 执行任务的核心逻辑，由 AgentRuntime worker 线程调用。
     */
    public abstract void execute();

    /**
     * 返回任务类型的可读名称，用于日志。
     */
    public abstract String getTaskType();

    // ---- Cancellation ----

    public void cancel() { this.cancelled = true; }
    public boolean isCancelled() { return cancelled; }

    // ---- Getters ----

    public String getTaskId() { return taskId; }
    public int getPriority() { return priority; }
    public long getOrder() { return order; }
    public AgentContext getContext() { return context; }

    // ---- Comparable (优先级队列排序) ----

    @Override
    public int compareTo(AgentTask other) {
        int priorityCompare = Integer.compare(this.priority, other.priority);
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        return Long.compare(this.order, other.order);
    }

    @Override
    public String toString() {
        return getTaskType() + "[" + taskId + ", pri=" + priority + "]";
    }
}
