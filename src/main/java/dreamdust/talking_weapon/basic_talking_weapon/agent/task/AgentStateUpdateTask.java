package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

/**
 * Agent 状态更新任务：不需要 LLM 调用的本地操作。
 * <p>
 * 根据最新的 AgentContext 快照更新 AgentState（情绪、熟悉度、战斗计数等）。
 * 零网络开销，执行速度极快。
 */
public class AgentStateUpdateTask extends AgentTask {

    /** 与 MemoryUpdate 同级的高优先级 */
    public static final int DEFAULT_PRIORITY = 5;

    /** 建议设置的新情绪 (可为 null 表示不修改) */
    private final AgentState.Mood suggestedMood;

    /** 熟悉度增量 (可以为 0 表示不修改) */
    private final int familiarityDelta;

    /** 建议设置的新目标 (可为 null 表示不修改) */
    private final String suggestedGoal;

    /** 对此次状态更新的文字描述 (可为 null) */
    private final String description;

    public AgentStateUpdateTask(AgentContext context,
                                AgentState.Mood suggestedMood,
                                int familiarityDelta,
                                String suggestedGoal) {
        this(context, suggestedMood, familiarityDelta, suggestedGoal, null);
    }

    public AgentStateUpdateTask(AgentContext context,
                                AgentState.Mood suggestedMood,
                                int familiarityDelta,
                                String suggestedGoal,
                                String description) {
        super(DEFAULT_PRIORITY, context);
        this.suggestedMood = suggestedMood;
        this.familiarityDelta = familiarityDelta;
        this.suggestedGoal = suggestedGoal;
        this.description = description;
    }

    /** 便捷构造：仅携带描述信息 */
    public AgentStateUpdateTask(AgentContext context, String description) {
        this(context, null, 0, null, description);
    }

    public AgentStateUpdateTask(AgentContext context) {
        this(context, null, 0, null, null);
    }

    @Override
    public void execute() {
        if (isCancelled()) return;

        // TODO: 实现状态更新逻辑
        // 1. 如果 suggestedMood != null，更新 AgentState.mood
        // 2. 如果 familiarityDelta != 0，累加 AgentState.familiarity
        // 3. 如果 suggestedGoal != null，更新 AgentState.currentGoal
        // 4. 根据 AgentContext 推断额外状态变化 (如 combatStreak)
    }

    @Override
    public String getTaskType() {
        return "AgentStateUpdate";
    }

    // ---- Getters ----

    public AgentState.Mood getSuggestedMood() { return suggestedMood; }
    public int getFamiliarityDelta() { return familiarityDelta; }
    public String getSuggestedGoal() { return suggestedGoal; }
    public String getDescription() { return description; }
}
