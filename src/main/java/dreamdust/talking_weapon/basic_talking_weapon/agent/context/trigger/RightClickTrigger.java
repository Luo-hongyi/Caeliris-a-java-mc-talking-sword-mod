package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.BaseContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.NearbyEntitiesCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.PlayerEnvironmentCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.ScreenshotCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.LLMInteractionTask;

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
        return List.of(
                new BaseContextCollector(), // 基础状态（生命、坐标、时间等）
                new ScreenshotCollector(), // 视觉输入（当前视角截图）
                new NearbyEntitiesCollector(), // 附近实体（怪物、动物、NPC）
                new PlayerEnvironmentCollector() // 环境信息（天气、光照、声音等）
        );
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        return new LLMInteractionTask(context);
    }
}
