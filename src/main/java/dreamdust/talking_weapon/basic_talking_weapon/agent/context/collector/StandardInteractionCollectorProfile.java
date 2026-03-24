package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 标准交互上下文采集配置。
 * <p>
 * 这套配置用于构造“与常规 LLM 交互尽量一致”的 collector 组合，
 * 同时允许内部调用方按需关闭截图或 prompt 专属条目。
 */
public final class StandardInteractionCollectorProfile {

    private StandardInteractionCollectorProfile() {
    }

    public static List<IContextCollector> build(boolean includeScreenshot, boolean includeRecentActions) {
        List<IContextCollector> list = new ArrayList<>();
        if (TalkingWeaponConfig.RC_ENABLE_BASE.get()) {
            list.add(new BaseContextCollector());
        }
        if (includeScreenshot && TalkingWeaponConfig.RC_ENABLE_SCREENSHOT.get()) {
            list.add(new ScreenshotCollector());
        }
        if (TalkingWeaponConfig.RC_ENABLE_NEARBY_ENTITIES.get()) {
            list.add(new NearbyEntitiesCollector());
        }
        if (TalkingWeaponConfig.RC_ENABLE_PLAYER_ENVIRONMENT.get()) {
            list.add(new PlayerEnvironmentCollector());
        }
        if (TalkingWeaponConfig.RC_ENABLE_NEARBY_STRUCTURES.get()) {
            list.add(new NearbyStructureCollector(
                    TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get(),
                    TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        }
        if (TalkingWeaponConfig.RC_ENABLE_NEARBY_POI.get()) {
            list.add(new NearbyPoiCollector(
                    TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get(),
                    TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        }
        if (TalkingWeaponConfig.RC_ENABLE_LOOKING_AT.get()) {
            list.add(new LookingAtCollector());
        }
        if (TalkingWeaponConfig.RC_ENABLE_SURROUNDING_BLOCKS.get()) {
            list.add(new SurroundingBlocksCollector());
        }
        if (includeRecentActions && TalkingWeaponConfig.RC_ENABLE_RECENT_ACTIONS.get()) {
            list.add(new RecentActionCollector());
        }
        return list;
    }
}
