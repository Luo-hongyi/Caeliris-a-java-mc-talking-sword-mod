package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.Collections;
import java.util.List;

/**
 * 定时反思触发器（旧版过渡骨架）。
 * <p>
 * 由 {@code TickEventWatcher} 在距上次反思超过 N 分钟时创建并 fire。
 * 后续应改为将 episode 切片、动作时间线摘要和近期对话打包发给 LLM 做深度沉思，
 * 最终生成一条
 * {@link dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry}。
 * <p>
 * <b>当前阶段</b>：仍属于旧版 LTM 生成路线的过渡实现。
 * 它会创建 {@link dreamdust.talking_weapon.basic_talking_weapon.agent.task.ReflectionTask}，
 * 但尚未接入当前版本的自动调度链，也未完成与最新记忆架构的统一。
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
        return new dreamdust.talking_weapon.basic_talking_weapon.agent.task.ReflectionTask(context);
    }

    @Override
    public List<String> getEnabledActions() {
        return List.of("favorability_settlement");
    }

    @Override
    public String getUserMessage() {
        return "（你开始沉入回忆）";
    }

    @Override
    public String getEventInstruction() {
        return "回顾你最近经历的这段旅途，写下你的内心独白。";
    }
}
