package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.BaseContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.LookingAtCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.NearbyEntitiesCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.NearbyPoiCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.NearbyStructureCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.PlayerEnvironmentCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.ScreenshotCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.SurroundingBlocksCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.LLMInteractionTask;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
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
        List<IContextCollector> list = new ArrayList<>();
        if (TalkingWeaponConfig.RC_ENABLE_BASE.get())              list.add(new BaseContextCollector());
        if (TalkingWeaponConfig.RC_ENABLE_SCREENSHOT.get())        list.add(new ScreenshotCollector());
        if (TalkingWeaponConfig.RC_ENABLE_NEARBY_ENTITIES.get())   list.add(new NearbyEntitiesCollector());
        if (TalkingWeaponConfig.RC_ENABLE_PLAYER_ENVIRONMENT.get()) list.add(new PlayerEnvironmentCollector());
        if (TalkingWeaponConfig.RC_ENABLE_NEARBY_STRUCTURES.get())   list.add(new NearbyStructureCollector(
                TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get(),
                TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        if (TalkingWeaponConfig.RC_ENABLE_NEARBY_POI.get())          list.add(new NearbyPoiCollector(
            TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get(),
            TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        if (TalkingWeaponConfig.RC_ENABLE_LOOKING_AT.get())          list.add(new LookingAtCollector());
        if (TalkingWeaponConfig.RC_ENABLE_SURROUNDING_BLOCKS.get())  list.add(new SurroundingBlocksCollector());
        return list;
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        return new LLMInteractionTask(context);
    }

    @Override
    public String getUserPrompt() {
        return TalkingWeaponConfig.RIGHT_CLICK_PROMPT.get();
    }
}
