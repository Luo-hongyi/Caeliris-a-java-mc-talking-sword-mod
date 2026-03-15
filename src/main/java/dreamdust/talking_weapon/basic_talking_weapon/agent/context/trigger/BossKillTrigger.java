package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.Collections;
import java.util.List;

/**
 * Boss 击杀触发器（骨架）。
 * <p>
 * 当 ActionBuffer 中检测到 Boss 级别实体被击杀时触发，
 * 让剑对重大战斗形成深刻记忆。
 * <p>
 * <b>当前阶段</b>：仅骨架。
 */
public class BossKillTrigger implements IAgentTrigger {

    private final String bossEntityId;

    public BossKillTrigger(String bossEntityId) {
        this.bossEntityId = bossEntityId;
    }

    @Override
    public String getSourceName() {
        return "boss_kill";
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
        // TODO: 后续迭代实现
        return null;
    }

    @Override
    public String getUserMessage() {
        return "（你刚刚击败了一个强大的敌人）";
    }

    @Override
    public String getEventInstruction() {
        return "你刚刚见证了 " + bossEntityId + " 被击败——这是一场史诗级的战斗！";
    }
}
