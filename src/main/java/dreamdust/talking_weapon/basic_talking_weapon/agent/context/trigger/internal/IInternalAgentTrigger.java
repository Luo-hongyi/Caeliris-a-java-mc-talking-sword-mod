package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.internal;

import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

/**
 * Agent 内部触发器。
 * <p>
 * 与前台的 {@code IAgentTrigger} 不同，这类 trigger：
 * <ul>
 * <li>不走 AgentSensor</li>
 * <li>不声明 collectors</li>
 * <li>只负责把内部状态变化显式包装成后台任务</li>
 * </ul>
 */
public interface IInternalAgentTrigger {

    /**
     * 内部 trigger 的稳定源名。
     */
    String getSourceName();

    /**
     * 基于已经准备好的内部信号，创建后台任务。
     */
    AgentTask createTask();

    /**
     * 显式提交到后台队列，避免“隐形任务生成”。
     */
    default boolean submit(AgentRuntime runtime) {
        if (runtime == null) {
            return false;
        }
        AgentTask task = createTask();
        return task != null && runtime.submitBackground(task);
    }
}
