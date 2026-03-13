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
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.LLMInteractionTask;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 群系变化触发器：玩家进入新群系时自动触发。
 * <p>
 * 仅需要基础上下文（无截图），调用 LLM 对新群系发表评论。
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
        List<IContextCollector> list = new ArrayList<>();
        if (TalkingWeaponConfig.BC_ENABLE_BASE.get())
            list.add(new BaseContextCollector());
        if (TalkingWeaponConfig.BC_ENABLE_SCREENSHOT.get())
            list.add(new ScreenshotCollector());
        if (TalkingWeaponConfig.BC_ENABLE_NEARBY_ENTITIES.get())
            list.add(new NearbyEntitiesCollector());
        if (TalkingWeaponConfig.BC_ENABLE_PLAYER_ENVIRONMENT.get())
            list.add(new PlayerEnvironmentCollector());
        if (TalkingWeaponConfig.BC_ENABLE_NEARBY_STRUCTURES.get())
            list.add(new NearbyStructureCollector(
                    TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get(),
                    TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        if (TalkingWeaponConfig.BC_ENABLE_NEARBY_POI.get())
            list.add(new NearbyPoiCollector(
                    TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get(),
                    TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        if (TalkingWeaponConfig.BC_ENABLE_LOOKING_AT.get())
            list.add(new LookingAtCollector());
        if (TalkingWeaponConfig.BC_ENABLE_SURROUNDING_BLOCKS.get())
            list.add(new SurroundingBlocksCollector());
        return list;
    }

    @Override
    public AgentTask createTask(AgentContext context) {
        return new LLMInteractionTask(context);
    }

    @Override
    public String getUserPrompt() {
        String template = PromptRegistry.getInstance().getBiomeChangePrompt();
        return template
                .replace("{old_biome}", oldBiome)
                .replace("{new_biome}", newBiome);
    }
}
