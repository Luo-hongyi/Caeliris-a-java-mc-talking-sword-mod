package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

/**
 * Agent 状态更新任务：不需要 LLM 调用的本地操作。
 * <p>
 * 根据规则驱动的逻辑同步更新 {@link AgentState}（熟悉度累加、情绪设置等）。
 * 零网络开销，执行速度极快。
 */
public class AgentStateUpdateTask extends AgentTask {

    /** 高优先级（先于 LLMInteractionTask 执行） */
    public static final int DEFAULT_PRIORITY = 5;

    /** 熟悉度增量（正数累加；0 表示不修改） */
    private final int familiarityDelta;

    /** 建议更新的情绪（可为 null 表示不修改） */
    private final AgentState.PrimaryEmotion suggestedEmotion;

    /** 建议更新的情绪强度（-1 表示不修改） */
    private final int suggestedIntensity;

    /** 对此次状态更新的文字描述（可为 null，仅日志用） */
    private final String description;

    // ── 构造器（全参） ──

    public AgentStateUpdateTask(AgentContext context,
            int familiarityDelta,
            AgentState.PrimaryEmotion suggestedEmotion,
            int suggestedIntensity,
            String description) {
        super(DEFAULT_PRIORITY, context);
        this.familiarityDelta = familiarityDelta;
        this.suggestedEmotion = suggestedEmotion;
        this.suggestedIntensity = suggestedIntensity;
        this.description = description;
    }

    // ── 便捷构造器：纯熟悉度累加 ──

    public AgentStateUpdateTask(AgentContext context, int familiarityDelta) {
        this(context, familiarityDelta, null, -1, null);
    }

    // ── 便捷构造器：仅描述（日志/标记用途） ──

    public AgentStateUpdateTask(AgentContext context, String description) {
        this(context, 0, null, -1, description);
    }

    public AgentStateUpdateTask(AgentContext context) {
        this(context, 0, null, -1, null);
    }

    @Override
    public void execute() {
        if (isCancelled())
            return;

        AgentState state = AgentRuntime.getInstance().getState();

        // 1. 熟悉度累加（代码规则驱动，每次触发时 +delta）
        if (familiarityDelta != 0) {
            state.incrementFamiliarity(familiarityDelta);
        }

        // 2. 情绪更新（由触发器规则指定，非 LLM 驱动的版本）
        if (suggestedEmotion != null) {
            state.setPrimaryEmotion(suggestedEmotion);
        }
        if (suggestedIntensity >= 0) {
            state.setEmotionIntensity(suggestedIntensity);
        }

        if (description != null && !description.isBlank()) {
            System.out.println("[TalkingWeapon] AgentStateUpdate: " + description
                    + " | familiarity=" + state.getFamiliarity()
                    + " | emotion=" + state.getPrimaryEmotion() + "(" + state.getEmotionIntensity() + ")");
        }
    }

    @Override
    public String getTaskType() {
        return "AgentStateUpdate";
    }

    // ── Getters ──

    public int getFamiliarityDelta() {
        return familiarityDelta;
    }

    public AgentState.PrimaryEmotion getSuggestedEmotion() {
        return suggestedEmotion;
    }

    public int getSuggestedIntensity() {
        return suggestedIntensity;
    }

    public String getDescription() {
        return description;
    }
}
