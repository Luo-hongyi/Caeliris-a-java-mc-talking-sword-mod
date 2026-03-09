package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;

/**
 * 反思任务：后台使用 LLM 对短期记忆进行压缩，生成长期记忆摘要。
 * <p>
 * 低优先级任务，在空闲时执行。将短期记忆窗口中的对话压缩为一段语义摘要，
 * 写入长期记忆层，从而在不丢失关键信息的前提下控制 token 消耗。
 */
public class ReflectionTask extends AgentTask {

    /** 最低优先级 (数字最大)，在空闲时执行 */
    public static final int DEFAULT_PRIORITY = 50;

    public ReflectionTask(AgentContext context) {
        super(DEFAULT_PRIORITY, context);
    }

    @Override
    public void execute() {
        if (isCancelled()) return;

        // TODO: 实现反思/压缩逻辑
        // 1. 读取 AgentMemory.shortTerm 中较旧的消息
        // 2. 调用 LLM 将多条消息压缩为一段摘要
        // 3. 将摘要写入 AgentMemory.longTerm
        // 4. 从 shortTerm 中移除已压缩的消息
    }

    @Override
    public String getTaskType() {
        return "Reflection";
    }
}
