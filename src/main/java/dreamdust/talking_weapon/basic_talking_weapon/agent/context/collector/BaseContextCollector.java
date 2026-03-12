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
            if (healthPct < 0.1f) {
                healthState = "health_critical";
                healthImportance = 1.0f;
            } else if (healthPct < 0.3f) {
                healthState = "health_low";
                healthImportance = 0.7f;
            } else if (healthPct < 0.8f) {
                healthState = "health_medium";
                healthImportance = 0.2f;
            } else {
                healthState = "health_full";
                healthImportance = 0.1f;
            }
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.STATE, healthState)
                    .description("玩家生命值: " + String.format("%.0f/%.0f (%.0f%%)",
                            player.getHealth(), player.getMaxHealth(), healthPct * 100))
                    .importance(healthImportance)
                    .build());
        }

        if (level != null) {
            long dayTime = level.getDayTime();
            builder.dayTime(dayTime)
                    .raining(level.isRaining());

            // 将 tick 转为直观的时段描述
            String timeDesc = describeTimeOfDay(dayTime);
            builder.extra("time_of_day", timeDesc);

            // ── 标签：时间状态 ──
            String timeState = categorizeTime(dayTime);
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.STATE, timeState)
                    .description("游戏内时间: " + timeDesc)
                    .importance(0.2f)
                    .build());

            // ── 标签：天气 ──
            String weatherState = level.isThundering() ? "weather_thunder"
                    : level.isRaining() ? "weather_rain" : "weather_clear";
            float weatherImportance = level.isThundering() ? 0.5f
                    : level.isRaining() ? 0.3f : 0.1f;
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.STATE, weatherState)
                    .description("天气: " + (level.isThundering() ? "雷暴风雨" : level.isRaining() ? "雨天" : "晴天"))
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
                            .description("当前群系: " + biomeId)
                            .importance(0.3f)
                            .build());
                });
            }
        }
    }

    /**
     * 将 Minecraft dayTime (tick) 转为可读的时段描述。
     * <p>
     * Minecraft 一天 = 24000 tick，0 tick = 早上 6:00。
     * <ul>
     * <li>0–2000 (06:00–07:12) → 清晨</li>
     * <li>2000–6000 (07:12–12:00) → 上午</li>
     * <li>6000–7000 (12:00–13:12) → 中午</li>
     * <li>7000–11000 (13:12–17:24) → 下午</li>
     * <li>11000–13000(17:24–19:48) → 傍晚</li>
     * <li>13000–17000(19:48–00:00) → 夜晚</li>
     * <li>17000–22000(00:00–04:48) → 深夜</li>
     * <li>22000–24000(04:48–06:00) → 黎明</li>
     * </ul>
     */
    private String describeTimeOfDay(long dayTime) {
        long time = dayTime % 24000;
        // 转换为 Minecraft 内的小时:分钟（0 tick = 6:00 AM）
        long totalMinutes = (time * 60) / 1000; // 每 1000 tick = 1 小时 = 60 分钟
        long hour = (6 + totalMinutes / 60) % 24;
        long minute = totalMinutes % 60;
        String clock = String.format("%02d:%02d", hour, minute);

        String period;
        if (time < 2000) {
            period = "清晨";
        } else if (time < 6000) {
            period = "上午";
        } else if (time < 7000) {
            period = "中午";
        } else if (time < 11000) {
            period = "下午";
        } else if (time < 13000) {
            period = "傍晚";
        } else if (time < 17000) {
            period = "夜晚";
        } else if (time < 22000) {
            period = "深夜";
        } else {
            period = "黎明";
        }

        return period + " (" + clock + ")";
    }

    /**
     * 将 Minecraft dayTime (tick) 转为粗粒度的时间状态标签。
     */
    private String categorizeTime(long dayTime) {
        long time = dayTime % 24000;
        if (time < 13000)
            return "time_day";
        return "time_night";
    }
}
