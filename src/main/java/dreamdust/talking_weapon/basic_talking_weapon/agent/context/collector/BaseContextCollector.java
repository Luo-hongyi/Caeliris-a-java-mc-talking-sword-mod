package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
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

            // ── 标签：生命状态 ──
            float healthPct = player.getHealth() / Math.max(player.getMaxHealth(), 1f);
            String healthState;
            float healthImportance;
            String hpDesc;
            if (healthPct < 0.2f) {
                healthState = "health_critical";
                healthImportance = 1.0f;
                hpDesc = "主人的生命力犹如风中残烛，极其虚弱！(" + String.format("%.0f/%.0f)", player.getHealth(), player.getMaxHealth());
            } else if (healthPct < 0.5f) {
                healthState = "health_low";
                healthImportance = 0.6f;
                hpDesc = "主人受了不轻的伤，需要治疗。(" + String.format("%.0f/%.0f)", player.getHealth(), player.getMaxHealth());
            } else if (healthPct < 0.8f) {
                healthState = "health_medium";
                healthImportance = 0.2f;
                hpDesc = "主人的生命状况有轻微折损。(" + String.format("%.0f/%.0f)", player.getHealth(), player.getMaxHealth());
            } else {
                healthState = "health_full";
                healthImportance = 0.1f;
                hpDesc = "主人的生命体征非常平稳健康。";
            }
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.STATE, healthState)
                    .description(hpDesc)
                    .importance(healthImportance)
                    .build());
                    
            // ── 标签：饥饿状态 ──
            int food = player.getFoodData().getFoodLevel();
            if (food < 6) {
                builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.STATE, "hungry_critical")
                    .description("主人已经非常饥饿，甚至没有力气奔跑了！(饥饿值: " + food + ")")
                    .importance(0.8f)
                    .build());
            } else if (food < 15) {
                builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.STATE, "hungry_low")
                    .description("主人的体力消耗明显，肚子饿了。(饥饿值: " + food + ")")
                    .importance(0.4f)
                    .build());
            }
        }

        if (level != null) {
            long dayTime = level.getDayTime();
            builder.dayTime(dayTime)
                    .raining(level.isRaining());

            // 将 tick 转为直观的时段描述
            String timeDesc = describeTimeOfDay(dayTime);

            // ── 标签：时间状态 ──
            String timeState = categorizeTime(dayTime);
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.STATE, timeState)
                    .description("感知到世界的时间是: " + timeDesc)
                    .importance(0.2f)
                    .build());

            // ── 标签：天气 ──
            String weatherState = level.isThundering() ? "weather_thunder"
                    : level.isRaining() ? "weather_rain" : "weather_clear";
            float weatherImportance = level.isThundering() ? 0.3f
                    : level.isRaining() ? 0.3f : 0.1f;
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.STATE, weatherState)
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
                            .detail(DetailTag.BIOME, biomeId)
                            .description("主人正身处于群落: " + biomeId)
                            .importance(0.2f)
                            .build());
                });
            }
        }
    }

    /**
     * 将 Minecraft dayTime (tick) 转为可读的时段描述。
     * 细分版本：深夜，凌晨，日出，早晨，中午，下午，日落，夜晚
     * <p>
     * Minecraft 一天 = 24000 tick，0 tick = 早上 6:00。
     * - 0–2000 (06:00–07:12) → 日出
     * - 2000–5000 (07:12–11:00) → 早晨
     * - 5000–7000 (11:00–13:12) → 中午
     * - 7000–11000 (13:12–17:24) → 下午
     * - 11000–13000 (17:24–19:48) → 日落
     * - 13000–18000 (19:48–00:48) → 夜晚
     * - 18000–22000 (00:48–04:48) → 深夜
     * - 22000–24000 (04:48–06:00) → 凌晨
     */
    private String describeTimeOfDay(long dayTime) {
        long time = dayTime % 24000;
        long totalMinutes = (time * 60) / 1000; // 每 1000 tick = 1 小时 = 60 分钟
        long hour = (6 + totalMinutes / 60) % 24;
        long minute = totalMinutes % 60;
        String clock = String.format("%02d:%02d", hour, minute);

        String period;
        if (time < 2000) period = "日出";
        else if (time < 5000) period = "早晨";
        else if (time < 7000) period = "中午";
        else if (time < 11000) period = "下午";
        else if (time < 13000) period = "日落";
        else if (time < 18000) period = "夜晚";
        else if (time < 22000) period = "深夜";
        else period = "凌晨";

        return period + " (" + clock + ")";
    }

    /**
     * 将 Minecraft dayTime (tick) 转为时间状态标签。
     */
    private String categorizeTime(long dayTime) {
        long time = dayTime % 24000;
        if (time < 2000) return "time_sunrise";
        if (time < 5000) return "time_morning";
        if (time < 7000) return "time_noon";
        if (time < 11000) return "time_afternoon";
        if (time < 13000) return "time_sunset";
        if (time < 18000) return "time_night";
        if (time < 22000) return "time_midnight";
        return "time_dawn";
    }
}
