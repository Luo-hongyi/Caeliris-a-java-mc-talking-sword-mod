package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

/**
 * 单个 episode split reason 的运行时参数。
 */
public record EpisodeReasonRuntimeConfig(
        boolean highPriority,
        int confirmDelaySegments,
        int coverBeforeSegments,
        int coverAfterSegments) {

    public static final int MIN_CONFIRM_DELAY_SEGMENTS = 0;
    public static final int MAX_CONFIRM_DELAY_SEGMENTS = 24;
    public static final int MIN_COVER_SEGMENTS = 0;
    public static final int MAX_COVER_SEGMENTS = 24;

    public EpisodeReasonRuntimeConfig {
        confirmDelaySegments = clampInt(
                confirmDelaySegments,
                MIN_CONFIRM_DELAY_SEGMENTS,
                MAX_CONFIRM_DELAY_SEGMENTS);
        coverBeforeSegments = clampInt(
                coverBeforeSegments,
                MIN_COVER_SEGMENTS,
                MAX_COVER_SEGMENTS);
        coverAfterSegments = clampInt(
                coverAfterSegments,
                MIN_COVER_SEGMENTS,
                MAX_COVER_SEGMENTS);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
