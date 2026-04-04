package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Episode splitter 的统一运行时参数。
 * <p>
 * authored 真源位于 Studio workspace 的 `episode_tuning.json`。
 */
public final class EpisodeRuntimeSettings {

    public static final int DEFAULT_FALLBACK_QUIET_WINDOW_SEGMENTS = 8;
    public static final int DEFAULT_SIGNAL_HOLDBACK_REGION_COUNT = 2;
    public static final double DEFAULT_EMPTY_STM_REJECT_PROBABILITY = 0.0D;

    private static final EnumMap<EpisodeSplitReason, EpisodeReasonRuntimeConfig> DEFAULT_CONFIGS =
            buildDefaultConfigs();
    private static final EnumMap<EpisodeSplitReason, EpisodeReasonRuntimeConfig> runtimeConfigs =
            new EnumMap<>(EpisodeSplitReason.class);
    private static volatile int fallbackQuietWindowSegments = DEFAULT_FALLBACK_QUIET_WINDOW_SEGMENTS;
    private static volatile int signalHoldbackRegionCount = DEFAULT_SIGNAL_HOLDBACK_REGION_COUNT;
    private static volatile double emptyStmRejectProbability = DEFAULT_EMPTY_STM_REJECT_PROBABILITY;

    static {
        resetToDefaults();
    }

    private EpisodeRuntimeSettings() {
    }

    public static synchronized EpisodeReasonRuntimeConfig getConfig(EpisodeSplitReason reason) {
        return copyConfig(configOrDefault(reason));
    }

    public static synchronized Map<EpisodeSplitReason, EpisodeReasonRuntimeConfig> snapshotConfigs() {
        EnumMap<EpisodeSplitReason, EpisodeReasonRuntimeConfig> snapshot = new EnumMap<>(EpisodeSplitReason.class);
        for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
            snapshot.put(reason, copyConfig(configOrDefault(reason)));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public static synchronized void setConfig(EpisodeSplitReason reason, EpisodeReasonRuntimeConfig config) {
        if (reason == null) {
            return;
        }
        runtimeConfigs.put(reason, copyConfig(normalizeOrDefault(reason, config)));
    }

    public static synchronized void replaceAllConfigs(Map<EpisodeSplitReason, EpisodeReasonRuntimeConfig> configs) {
        runtimeConfigs.clear();
        for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
            EpisodeReasonRuntimeConfig nextConfig = configs == null ? null : configs.get(reason);
            runtimeConfigs.put(reason, copyConfig(normalizeOrDefault(reason, nextConfig)));
        }
    }

    public static synchronized void resetToDefaults() {
        runtimeConfigs.clear();
        for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
            runtimeConfigs.put(reason, copyConfig(DEFAULT_CONFIGS.get(reason)));
        }
        fallbackQuietWindowSegments = DEFAULT_FALLBACK_QUIET_WINDOW_SEGMENTS;
        signalHoldbackRegionCount = DEFAULT_SIGNAL_HOLDBACK_REGION_COUNT;
        emptyStmRejectProbability = DEFAULT_EMPTY_STM_REJECT_PROBABILITY;
    }

    public static int getFallbackQuietWindowSegments() {
        return fallbackQuietWindowSegments;
    }

    public static void setFallbackQuietWindowSegments(int value) {
        fallbackQuietWindowSegments = clampInt(value, 1, 64);
    }

    public static int getSignalHoldbackRegionCount() {
        return signalHoldbackRegionCount;
    }

    public static void setSignalHoldbackRegionCount(int value) {
        signalHoldbackRegionCount = clampInt(value, 0, 64);
    }

    public static double getEmptyStmRejectProbability() {
        return emptyStmRejectProbability;
    }

    public static void setEmptyStmRejectProbability(double value) {
        emptyStmRejectProbability = clampDouble(value, 0.0D, 1.0D);
    }

    public static EpisodeReasonRuntimeConfig defaultConfig(EpisodeSplitReason reason) {
        return copyConfig(normalizeOrDefault(reason, null));
    }

    private static EpisodeReasonRuntimeConfig configOrDefault(EpisodeSplitReason reason) {
        if (reason == null) {
            return new EpisodeReasonRuntimeConfig(false, 0, 0, 0);
        }
        EpisodeReasonRuntimeConfig config = runtimeConfigs.get(reason);
        return config != null ? config : normalizeOrDefault(reason, null);
    }

    private static EpisodeReasonRuntimeConfig normalizeOrDefault(
            EpisodeSplitReason reason,
            EpisodeReasonRuntimeConfig config) {
        EpisodeReasonRuntimeConfig fallback = DEFAULT_CONFIGS.get(reason);
        if (config == null) {
            return fallback != null ? fallback : new EpisodeReasonRuntimeConfig(false, 0, 0, 0);
        }
        return new EpisodeReasonRuntimeConfig(
                config.highPriority(),
                config.confirmDelaySegments(),
                config.coverBeforeSegments(),
                config.coverAfterSegments());
    }

    private static EpisodeReasonRuntimeConfig copyConfig(EpisodeReasonRuntimeConfig config) {
        if (config == null) {
            return new EpisodeReasonRuntimeConfig(false, 0, 0, 0);
        }
        return new EpisodeReasonRuntimeConfig(
                config.highPriority(),
                config.confirmDelaySegments(),
                config.coverBeforeSegments(),
                config.coverAfterSegments());
    }

    private static EnumMap<EpisodeSplitReason, EpisodeReasonRuntimeConfig> buildDefaultConfigs() {
        EnumMap<EpisodeSplitReason, EpisodeReasonRuntimeConfig> defaults =
                new EnumMap<>(EpisodeSplitReason.class);
        defaults.put(EpisodeSplitReason.DEATH, new EpisodeReasonRuntimeConfig(true, 1, 1, 1));
        defaults.put(EpisodeSplitReason.ENTER_REGISTERED_LOCATION, new EpisodeReasonRuntimeConfig(true, 0, 1, 1));
        defaults.put(EpisodeSplitReason.LEAVE_REGISTERED_LOCATION, new EpisodeReasonRuntimeConfig(true, 0, 1, 1));
        defaults.put(EpisodeSplitReason.ENTER_MAJOR_STRUCTURE, new EpisodeReasonRuntimeConfig(true, 0, 1, 1));
        defaults.put(EpisodeSplitReason.EXIT_MAJOR_STRUCTURE, new EpisodeReasonRuntimeConfig(true, 0, 1, 1));
        defaults.put(EpisodeSplitReason.INTENT_HARD_SHIFT, new EpisodeReasonRuntimeConfig(false, 1, 1, 1));
        defaults.put(EpisodeSplitReason.INTENT_SOFT_SHIFT, new EpisodeReasonRuntimeConfig(false, 1, 1, 1));
        defaults.put(EpisodeSplitReason.FALLBACK_QUIET_GAP, new EpisodeReasonRuntimeConfig(false, 1, 1, 1));
        return defaults;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
