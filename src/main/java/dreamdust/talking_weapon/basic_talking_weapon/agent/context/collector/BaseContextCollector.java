package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.WorldTimeSemantics;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * 基础上下文采集器：采集玩家核心状态。
 * <ul>
 * <li>生命值 / 最大生命值</li>
 * <li>饥饿值</li>
 * <li>坐标 (x, y, z)</li>
 * <li>所在群系</li>
 * <li>游戏内时间</li>
 * <li>天气（是否下雨）</li>
 * </ul>
 * 几乎所有触发器都应包含此采集器。
 */
public class BaseContextCollector implements IContextCollector {

    @Override
    public String getName() {
        return "base";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player != null) {
            builder.health(player.getHealth())
                    .maxHealth(player.getMaxHealth())
                    .foodLevel(player.getFoodData().getFoodLevel())
                    .pos(player.getX(), player.getY(), player.getZ());

            // ── 扩展数据：坐标与地面高度 ──
            int px = (int) player.getX(), py = (int) player.getY(), pz = (int) player.getZ();
            builder.extra("player_pos", String.format("(%d, %d, %d)", px, py, pz));

            // 从玩家脚下向下扫描找到地面 Y
            if (level != null) {
                net.minecraft.core.BlockPos scan = player.blockPosition().below();
                int groundY = py;
                for (int i = 0; i < 64; i++) {
                    if (!level.getBlockState(scan).isAir()) {
                        groundY = scan.getY();
                        break;
                    }
                    scan = scan.below();
                }
                builder.extra("ground_level", String.valueOf(groundY));

                String dimensionId = level.dimension().location().toString();
                LocationRegistry.ResolvedLocation resolvedLocation = LocationRegistry.getInstance()
                        .resolveCurrentLocation(player.getX(), player.getY(), player.getZ(), dimensionId,
                                LocationRegistry.DEFAULT_HORIZONTAL_RADIUS, LocationRegistry.DEFAULT_VERTICAL_RADIUS);
                if (resolvedLocation != null) {
                    builder.extra("registered_location_name", resolvedLocation.getName());
                    builder.extra("registered_location_prompt", resolvedLocation.toPromptString());

                    FocusTag locationFocus = FocusTag.LANDSCAPE;
                    String configuredFocus = resolvedLocation.getEntry().focusTag;
                    if (configuredFocus != null && !configuredFocus.isBlank()) {
                        try {
                            locationFocus = FocusTag.parse(configuredFocus.trim());
                        } catch (IllegalArgumentException ignored) {
                            locationFocus = FocusTag.LANDSCAPE;
                        }
                    }

                    TaggedContextEntry.Builder locationEntry = TaggedContextEntry.builder()
                            .focus(locationFocus)
                            .detail(DetailTag.customSpecific(resolvedLocation.getName()))
                            .description("主人当前位于已登记地点: " + resolvedLocation.getName())
                            .importance(ImportanceRegistry.getInstance().get("base:registered_location", 0.28f));
                    for (String rawTag : resolvedLocation.getEntry().getParsedDetailTags()) {
                        try {
                            locationEntry.detail(TagPolicy.parseRegisteredTag(rawTag));
                        } catch (IllegalArgumentException ignored) {
                            // 跳过非法或未登记的 location 附带标签，避免中断整次上下文采集。
                        }
                    }
                    builder.addTaggedEntry(locationEntry.build());
                }
            }

            // ── 标签：生命状态 ──
            float healthPct = player.getHealth() / Math.max(player.getMaxHealth(), 1f);
            String healthState;
            float healthImportance;
            String hpDesc;
            if (healthPct < 0.2f) {
                healthState = "health_critical";
                healthImportance = ImportanceRegistry.getInstance().get("base:health_critical", 1.0f);
                hpDesc = "主人的生命力犹如风中残烛，极其虚弱！(" + String.format("%.0f/%.0f)", player.getHealth(), player.getMaxHealth());
            } else if (healthPct < 0.5f) {
                healthState = "health_low";
                healthImportance = ImportanceRegistry.getInstance().get("base:health_low", 0.6f);
                hpDesc = "主人受了不轻的伤，需要治疗。(" + String.format("%.0f/%.0f)", player.getHealth(), player.getMaxHealth());
            } else if (healthPct < 0.8f) {
                healthState = "health_medium";
                healthImportance = ImportanceRegistry.getInstance().get("base:health_medium", 0.2f);
                hpDesc = "主人的生命状况有轻微折损。(" + String.format("%.0f/%.0f)", player.getHealth(), player.getMaxHealth());
            } else {
                healthState = "health_full";
                healthImportance = ImportanceRegistry.getInstance().get("base:health_good", 0.1f);
                hpDesc = "主人的生命体征非常平稳健康。";
            }
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.CUSTOM_SPECIFIC, healthState)
                    .description(hpDesc)
                    .importance(healthImportance)
                    .build());
                    
            // ── 标签：饥饿状态 ──
            int food = player.getFoodData().getFoodLevel();
            if (food < 6) {
                builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "hungry_critical")
                    .description("主人已经非常饥饿，甚至没有力气奔跑了！(饥饿值: " + food + ")")
                    .importance(ImportanceRegistry.getInstance().get("base:hunger_critical", 0.8f))
                    .build());
            } else if (food < 15) {
                builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "hungry_low")
                    .description("主人的体力消耗明显，肚子饿了。(饥饿值: " + food + ")")
                    .importance(ImportanceRegistry.getInstance().get("base:hunger_low", 0.4f))
                    .build());
            }
        }

        if (level != null) {
            long gameTime = level.getGameTime();
            long worldDayTime = level.getDayTime();
            long dayIndex = WorldTimeSemantics.dayIndex(gameTime);
            long timeOfDayTick = WorldTimeSemantics.timeOfDay(worldDayTime);
            builder.gameTime(gameTime)
                    .dayIndex(dayIndex)
                    .timeOfDayTick(timeOfDayTick)
                    .raining(level.isRaining());

            // 将 tick 转为直观的时段描述
            String timeDesc = WorldTimeSemantics.describeTimeOfDay(timeOfDayTick);
            builder.extra("time_desc", timeDesc);

            // ── 标签：时间状态 ──
            String timeState = WorldTimeSemantics.categorizeTime(timeOfDayTick);
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.CUSTOM_SPECIFIC, timeState)
                    .description("感知到世界的时间是: " + timeDesc)
                    .importance(ImportanceRegistry.getInstance().get("base:time", 0.2f))
                    .associativeOnly(true)
                    .build());

            // ── 标签：天气 ──
            String weatherState = level.isThundering() ? "weather_thunder"
                    : level.isRaining() ? "weather_rain" : "weather_clear";
            float weatherImportance = level.isThundering() ? ImportanceRegistry.getInstance().get("base:weather_bad", 0.3f)
                    : level.isRaining() ? ImportanceRegistry.getInstance().get("base:weather_bad", 0.3f) : ImportanceRegistry.getInstance().get("base:weather_good", 0.1f);
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.CUSTOM_SPECIFIC, weatherState)
                    .description("外面的天气: " + (level.isThundering() ? "正在电闪雷鸣的暴风雨" : level.isRaining() ? "正在下雨" : "晴朗万里"))
                    .importance(weatherImportance)
                    .build());

            if (player != null) {
                Holder<Biome> biomeHolder = level.getBiome(player.blockPosition());
                biomeHolder.unwrapKey().ifPresent(key -> {
                    String biomeId = key.location().toString();
                    builder.biome(biomeId);

                    // ── 标签：群系 ──
                    builder.addTaggedEntry(TaggedContextEntry.builder()
                            .focus(FocusTag.LANDSCAPE)
                            .detail(DetailTag.NATIVE_SPECIFIC, biomeId)
                            .description("主人正身处于群落: " + biomeId)
                            .importance(ImportanceRegistry.getInstance().get("base:biome", 0.2f))
                            .associativeOnly(true)
                            .build());
                });
            }
        }
    }

}
