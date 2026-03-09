package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

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
     * 返回此触发器专属的 user prompt。
     * <p>
     * 由 {@code AgentSensor} 在 fireTrigger 时写入
     * {@code AgentContext.extras["user_prompt"]}，
     * 供 {@code LLMInteractionTask} 读取。
     *
     * @return 用户提示词文本
     */
    default String getUserPrompt() {
        return "请根据当前状态，以武器灵魂的身份发表一到两句话的简短评论。";
    }
}
