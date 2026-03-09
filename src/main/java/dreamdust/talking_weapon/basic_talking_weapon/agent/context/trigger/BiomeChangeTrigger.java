package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.BaseContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentStateUpdateTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.List;

/**
 * 群系变化触发器：玩家进入新群系时自动触发。
 * <p>
 * 仅需要基础上下文（无截图），创建状态更新任务。
 */
public class BiomeChangeTrigger implements IAgentTrigger {

    private final String oldBiome;
    private final String newBiome;

    public BiomeChangeTrigger(String oldBiome, String newBiome) {
        this.oldBiome = oldBiome;
        this.newBiome = newBiome;
    }

    @Override
    public String getSourceName() {
        return "biome_change";
    }

    @Override
    public boolean requiresDebounce() {
        return false;
    }

    @Override
    public List<IContextCollector> getCollectors() {
        return List.of(new BaseContextCollector());
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        return new AgentStateUpdateTask(context,
                "发现自己进入了新群系：" + newBiome + "，之前是：" + oldBiome);
    }
}
