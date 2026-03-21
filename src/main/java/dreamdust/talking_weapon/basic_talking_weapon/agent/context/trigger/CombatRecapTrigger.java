package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.Collections;
import java.util.List;

/**
 * 连击/恶战回顾触发器（骨架）。
 * <p>
 * 当 RecentActionBuffer 中 {@code KILL} 计数达到阈值时触发，
 * 让剑对刚才的激战发表感慨并形成记忆。
 * <p>
 * <b>当前阶段</b>：仅骨架。
 */
public class CombatRecapTrigger implements IAgentTrigger {

    private final int killCount;

    public CombatRecapTrigger(int killCount) {
        this.killCount = killCount;
    }

    @Override
    public String getSourceName() {
        return "combat_recap";
    }

    @Override
    public boolean requiresDebounce() {
        return true;
    }

    @Override
    public List<IContextCollector> getCollectors() {
        return Collections.emptyList();
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        // TODO: 后续迭代实现
        return null;
    }

    @Override
    public String getUserMessage() {
        return "（你刚刚经历了一场恶战）";
    }

    @Override
    public String getEventInstruction() {
        return "你刚刚见证了 " + killCount + " 次击杀，回顾这场战斗。";
    }
}
