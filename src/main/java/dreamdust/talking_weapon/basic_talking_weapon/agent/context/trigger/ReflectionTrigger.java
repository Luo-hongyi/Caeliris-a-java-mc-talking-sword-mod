package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.Collections;
import java.util.List;

/**
 * 定时反思触发器（骨架）。
 * <p>
 * 由 {@code TickEventWatcher} 在距上次反思超过 N 分钟时创建并 fire。
 * 将 ActionBuffer 快照和近期对话打包发给 LLM 做深度沉思，
 * 最终生成一条
 * {@link dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemoryRecord}。
 * <p>
 * <b>当前阶段</b>：仅骨架，{@link #createTask(AgentContext)} 返回 null。
 */
public class ReflectionTrigger implements IAgentTrigger {

    @Override
    public String getSourceName() {
        return "reflection";
    }

    @Override
    public boolean requiresDebounce() {
        return false;
    }

    @Override
    public List<IContextCollector> getCollectors() {
        return Collections.emptyList();
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        // TODO: 后续迭代实现 ReflectionTask
        return null;
    }

    @Override
    public String getUserPrompt() {
        return "回顾你最近经历的这段旅途，写下你的内心独白。";
    }
}
