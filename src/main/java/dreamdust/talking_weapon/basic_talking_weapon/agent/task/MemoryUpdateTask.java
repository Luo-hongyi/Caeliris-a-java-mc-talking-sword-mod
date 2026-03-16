package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;

/**
 * 记忆更新任务：不需要 LLM 调用的本地操作。
 * <p>
 * 典型用途：将新的对话记录写入短期记忆，更新 AgentState 中的计数器等。
 * 零网络开销，执行速度极快。
 */
public class MemoryUpdateTask extends AgentTask {

    /** 比 LLM 任务更高的优先级 (更小的数字) */
    public static final int DEFAULT_PRIORITY = 5;

    /** 要追加的消息内容 (可为 null) */
    private final String messageContent;

    public MemoryUpdateTask(AgentContext context, String messageContent) {
        super(DEFAULT_PRIORITY, context);
        this.messageContent = messageContent;
    }

    @Override
    public void execute() {
        if (isCancelled()) return;

        // TODO: 实现本地记忆/状态更新逻辑
    }

    @Override
    public String getTaskType() {
        return "MemoryUpdate";
    }

    public String getMessageContent() {
        return messageContent;
    }
}
