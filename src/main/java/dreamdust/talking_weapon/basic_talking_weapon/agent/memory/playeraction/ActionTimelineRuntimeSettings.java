package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 玩家动作时间线在运行时使用的统一参数。
 * <p>
 * 这层参数当前由 Studio workspace 中的 authored tuning 文件驱动，
 * 供 fixed segment / moving window / episode intent 分析共用。
 */
public final class ActionTimelineRuntimeSettings {

    public static final long DEFAULT_FIXED_SEGMENT_MS = ActionArchiveCompactor.DEFAULT_FIXED_SEGMENT_MS;
    public static final long DEFAULT_MOVING_WINDOW_MS = MovingActionBuffer.DEFAULT_TIME_WINDOW_MS;
    public static final int DEFAULT_EPISODE_LENGTH_CAP_SEGMENTS = 8;
    public static final int DEFAULT_EPISODE_LENGTH_CAP_LOOKBACK_SEGMENTS = 2;
    public static final int DEFAULT_REGISTERED_LOCATION_LEAVE_EXTRA_BLOCKS = 32;
    public static final int DEFAULT_MAJOR_STRUCTURE_ENTER_RADIUS_BLOCKS = 24;
    public static final int DEFAULT_MAJOR_STRUCTURE_EXIT_RADIUS_BLOCKS = 48;
    public static final Set<String> DEFAULT_MAJOR_STRUCTURE_WHITELIST = defaultMajorStructureWhitelist();

    private static volatile long fixedSegmentMs = DEFAULT_FIXED_SEGMENT_MS;
    private static volatile long movingWindowMs = DEFAULT_MOVING_WINDOW_MS;
    private static volatile int episodeLengthCapSegments = DEFAULT_EPISODE_LENGTH_CAP_SEGMENTS;
    private static volatile int episodeLengthCapLookbackSegments = DEFAULT_EPISODE_LENGTH_CAP_LOOKBACK_SEGMENTS;
    private static volatile int registeredLocationLeaveExtraBlocks = DEFAULT_REGISTERED_LOCATION_LEAVE_EXTRA_BLOCKS;
    private static volatile int majorStructureEnterRadiusBlocks = DEFAULT_MAJOR_STRUCTURE_ENTER_RADIUS_BLOCKS;
    private static volatile int majorStructureExitRadiusBlocks = DEFAULT_MAJOR_STRUCTURE_EXIT_RADIUS_BLOCKS;
    private static volatile Set<String> majorStructureWhitelist = DEFAULT_MAJOR_STRUCTURE_WHITELIST;

    private ActionTimelineRuntimeSettings() {
    }

    public static long getFixedSegmentMs() {
        return fixedSegmentMs;
    }

    public static void setFixedSegmentMs(long value) {
        fixedSegmentMs = Math.max(1_000L, value);
    }

    public static long getMovingWindowMs() {
        return movingWindowMs;
    }

    public static void setMovingWindowMs(long value) {
        movingWindowMs = Math.max(1_000L, value);
    }

    public static int getFixedSegmentSeconds() {
        return (int) Math.max(1L, Math.round(fixedSegmentMs / 1_000.0));
    }

    public static int getMovingWindowSeconds() {
        return (int) Math.max(1L, Math.round(movingWindowMs / 1_000.0));
    }

    public static int getEpisodeLengthCapSegments() {
        return episodeLengthCapSegments;
    }

    public static void setEpisodeLengthCapSegments(int value) {
        int clamped = Math.max(2, Math.min(24, value));
        episodeLengthCapSegments = clamped;
        if (episodeLengthCapLookbackSegments >= clamped) {
            episodeLengthCapLookbackSegments = Math.max(1, clamped - 1);
        }
    }

    public static int getEpisodeLengthCapLookbackSegments() {
        return episodeLengthCapLookbackSegments;
    }

    public static void setEpisodeLengthCapLookbackSegments(int value) {
        int cap = Math.max(2, episodeLengthCapSegments);
        episodeLengthCapLookbackSegments = Math.max(1, Math.min(cap - 1, value));
    }

    public static int getRegisteredLocationLeaveExtraBlocks() {
        return registeredLocationLeaveExtraBlocks;
    }

    public static void setRegisteredLocationLeaveExtraBlocks(int value) {
        registeredLocationLeaveExtraBlocks = clampInt(value, 0, 256);
    }

    public static int getMajorStructureEnterRadiusBlocks() {
        return majorStructureEnterRadiusBlocks;
    }

    public static void setMajorStructureEnterRadiusBlocks(int value) {
        majorStructureEnterRadiusBlocks = clampInt(value, 0, 256);
        if (majorStructureExitRadiusBlocks < majorStructureEnterRadiusBlocks) {
            majorStructureExitRadiusBlocks = majorStructureEnterRadiusBlocks;
        }
    }

    public static int getMajorStructureExitRadiusBlocks() {
        return majorStructureExitRadiusBlocks;
    }

    public static void setMajorStructureExitRadiusBlocks(int value) {
        majorStructureExitRadiusBlocks = clampInt(value, majorStructureEnterRadiusBlocks, 256);
    }

    public static Set<String> getMajorStructureWhitelist() {
        return majorStructureWhitelist;
    }

    public static void setMajorStructureWhitelist(Collection<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String safe = normalizeTagString(value);
                if (safe != null) {
                    normalized.add(safe);
                }
            }
        }
        majorStructureWhitelist = normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }

    public static boolean isMajorStructureWhitelisted(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return false;
        }
        return majorStructureWhitelist.contains(DetailTag.nativeSpecific(structureId.trim()).toString());
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeTagString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Set<String> defaultMajorStructureWhitelist() {
        LinkedHashSet<String> whitelist = new LinkedHashSet<>();
        whitelist.add(DetailTag.nativeSpecific("minecraft:village_plains").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:village_desert").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:village_savanna").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:village_snowy").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:village_taiga").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:ancient_city").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:fortress").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:bastion_remnant").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:stronghold").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:end_city").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:woodland_mansion").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:ocean_monument").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:jungle_temple").toString());
        whitelist.add(DetailTag.nativeSpecific("minecraft:desert_pyramid").toString());
        return Collections.unmodifiableSet(whitelist);
    }
}
