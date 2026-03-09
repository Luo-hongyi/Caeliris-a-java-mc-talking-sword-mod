package dreamdust.talking_weapon.basic_talking_weapon.agent.action;

/**
 * Agent 动作基类：LLM 决策后要在游戏中执行的具体行为。
 * <p>
 * 所有动作都必须通过 {@code Minecraft.getInstance().tell()} 提交到主线程执行，
 * 因为 Minecraft 的渲染和游戏逻辑都运行在主线程上。
 */
public abstract class AgentAction {

    /** 动作类型标识 */
    private final String actionType;

    /** 动作创建时间 */
    private final long createdAt;

    protected AgentAction(String actionType) {
        this.actionType = actionType;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * 在主线程上执行此动作。
     * 实现者应保证此方法在 Minecraft 主线程中调用。
     */
    public abstract void perform();

    /**
     * 返回动作执行的预估耗时 (毫秒)，用于调度。
     */
    public abstract long estimatedDurationMs();

    // ---- Getters ----

    public String getActionType() { return actionType; }
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Action[" + actionType + "]";
    }
}
