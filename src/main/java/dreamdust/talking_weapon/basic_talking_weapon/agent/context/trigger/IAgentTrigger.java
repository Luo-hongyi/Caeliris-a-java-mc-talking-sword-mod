package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.LLMOutputSchema;


import java.util.EnumSet;
import java.util.List;

/**
 * 触发器接口：定义何种游戏事件产生何种 AgentTask，
 * 以及需要运行哪些 {@link IContextCollector} 来采集信息。
 * <p>
 * 触发器本身不直接采集数据，而是通过 {@link #getCollectors()}
 * 声明需要哪些采集器，由 {@code AgentSensor} 统一调度执行。
 */
public interface IAgentTrigger {

    /** 触发源名称 */
    String getSourceName();

    /** 是否需要防抖检查 */
    boolean requiresDebounce();

    /**
     * 声明此触发器需要哪些上下文采集器。
     * <p>
     * AgentSensor 会按顺序执行这些采集器，
     * 将结果写入 {@link AgentContext.Builder}。
     *
     * @return 采集器列表（顺序执行）
     */
    List<IContextCollector> getCollectors();

    /**
     * 通过采集好的 Context 生成对应的 AgentTask。
     */
    AgentTask createTask(AgentContext context);

    /**
     * 返回本触发器的“用户消息”（UserMessage 文本）。
     * <p>
     * 这段文本用于描述“玩家刚刚做了什么 / 发生了什么事件”，
     * 会由 {@code AgentSensor} 写入 {@code AgentContext.extras["user_message"]}，
     * 并作为 {@code LLMInteractionTask} 的 UserMessage 内容发送给模型。
     *
     * @return user message 文本
     */
    default String getUserMessage() {
        return "[OBSERVATION]";
    }

    /**
     * 返回本触发器的“事件指令”（system 事件段落）。
     * <p>
     * 这段文本用于告诉模型“本轮需要如何回应/输出哪些字段”，
     * 会由 {@code AgentSensor} 写入 {@code AgentContext.extras["event_instruction"]}，
     * 并由 {@code LLMInteractionTask} 注入 system prompt 的 [Event Instructions] 段落。
     *
     * @return event instruction 文本
     */
    default String getEventInstruction() {
        return "请根据当前状态，以武器灵魂的身份发表一到两句话的简短评论。";
    }

    /**
     * 声明此触发器期望 LLM 返回的输出字段集合（可合成输出格式）。
     * <p>
     * {@code LLMInteractionTask.buildSystemPrompt()} 将根据此集合动态拼装
     * JSON 格式指令，只要求 LLM 输出必要字段，避免冗余并节省 Token。
     * <p>
     * 默认包含 {@code THINKING}、{@code INNER_MONOLOGUE}、{@code SPEECH}，
     * 不包含 {@code EMOTION_UPDATE}。
     * <p>
     * 情绪化触发器（如受伤、BOSS 击杀等）应覆写此方法，
     * 加入 {@link LLMOutputSchema#EMOTION_UPDATE} 以启用情绪更新。
     *
     * @return 需要的输出字段枚举集
     */
    default EnumSet<LLMOutputSchema> getOutputSchema() {
        return LLMOutputSchema.defaultSchema();
    }
}
