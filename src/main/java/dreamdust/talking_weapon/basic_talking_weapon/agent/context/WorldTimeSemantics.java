package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

/**
 * 统一的 Minecraft 游戏内时间语义工具。
 * <p>
 * 用于 Collector、LTM 以及 Prompt 合成阶段共享同一套“游戏时间 -> 可读语义”的规则，
 * 避免各处对时段、天数和显示文案的解释漂移。
 */
public final class WorldTimeSemantics {

    public static final long TICKS_PER_DAY = 24000L;

    private WorldTimeSemantics() {
    }

    /**
     * 世界中的第几天。返回 1-based 结果，便于直接用于叙事文本。
     */
    public static long dayIndex(long gameTime) {
        if (gameTime < 0L) {
            return -1L;
        }
        return Math.floorDiv(gameTime, TICKS_PER_DAY) + 1L;
    }

    /**
     * 当天时段的 tick 值，范围 [0, 23999]。
     */
    public static long timeOfDay(long gameTime) {
        if (gameTime < 0L) {
            return -1L;
        }
        return Math.floorMod(gameTime, TICKS_PER_DAY);
    }

    /**
     * 将 Minecraft gameTime/dayTime 转为可读的时段描述。
     * 示例：{@code 深夜 (02:30)}
     */
    public static String describeTimeOfDay(long gameTime) {
        if (gameTime < 0L) {
            return "";
        }

        long time = timeOfDay(gameTime);
        long totalMinutes = (time * 60) / 1000;
        long hour = (6 + totalMinutes / 60) % 24;
        long minute = totalMinutes % 60;
        String clock = String.format("%02d:%02d", hour, minute);

        String period;
        if (time < 2000) {
            period = "日出";
        } else if (time < 5000) {
            period = "早晨";
        } else if (time < 7000) {
            period = "中午";
        } else if (time < 11000) {
            period = "下午";
        } else if (time < 13000) {
            period = "日落";
        } else if (time < 18000) {
            period = "夜晚";
        } else if (time < 22000) {
            period = "深夜";
        } else {
            period = "凌晨";
        }

        return period + " (" + clock + ")";
    }

    /**
     * 用于 DetailTag 的时间状态标签。
     */
    public static String categorizeTime(long gameTime) {
        if (gameTime < 0L) {
            return "time_unknown";
        }

        long time = timeOfDay(gameTime);
        if (time < 2000) {
            return "time_sunrise";
        }
        if (time < 5000) {
            return "time_morning";
        }
        if (time < 7000) {
            return "time_noon";
        }
        if (time < 11000) {
            return "time_afternoon";
        }
        if (time < 13000) {
            return "time_sunset";
        }
        if (time < 18000) {
            return "time_night";
        }
        if (time < 22000) {
            return "time_midnight";
        }
        return "time_dawn";
    }

    /**
     * 为长期记忆构造更具叙事感的时间锚点。
     * 示例：{@code 第23天的深夜 (02:30)}
     */
    public static String describeMemoryTimeAnchor(long gameTime) {
        if (gameTime < 0L) {
            return "";
        }

        long day = dayIndex(gameTime);
        String timeDesc = describeTimeOfDay(gameTime);
        if (day <= 0L || timeDesc.isBlank()) {
            return "";
        }
        return "第" + day + "天的" + timeDesc;
    }
}
