package dreamdust.talking_weapon.basic_talking_weapon.agent.action.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.action.task.LLMInteractionTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.StandardInteractionCollectorProfile;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.IAgentTrigger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import java.util.List;

/**
 * 右键触发器：玩家右键使用武器时触发。
 * <p>
 * 采集全量上下文（基础状态 + 截图 + 附近实体 + 环境信息），
 * 然后创建 LLM 交互任务。
 */
public class RightClickTrigger implements IAgentTrigger {

    @Override
    public String getSourceName() {
        return "right_click";
    }

    @Override
    public boolean requiresDebounce() {
        return true;
    }

    @Override
    public List<IContextCollector> getCollectors() {
        return StandardInteractionCollectorProfile.build(true, true);
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        return new LLMInteractionTask(context);
    }

    @Override
    public String getUserMessage() {
        return PromptRegistry.getInstance().getTriggerUserMessage(getSourceName());
    }

    @Override
    public String getEventInstruction() {
        return PromptRegistry.getInstance().getTriggerEventInstruction(getSourceName());
    }

    @Override
    public List<String> getEnabledActions() {
        return java.util.List.of("speech", "inner_monologue");
    }
}
