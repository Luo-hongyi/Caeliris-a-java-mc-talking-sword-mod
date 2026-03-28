package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate.EpisodeSynthesisGateInput;

/**
 * 一段可被 owner-side 清理的正式源素材窗口。
 * <p>
 * 这里冻结两套边界：
 * - fixed segment 侧用 `startSegmentId .. endSegmentId`
 * - raw action 侧用 `[startTimestamp, rawActionSliceExclusiveTimestamp)`
 * - STM 侧用 `[stmSliceStartTimestamp, stmSliceExclusiveTimestamp)`
 */
public record MemorySourceMaterialWindow(
        long startSegmentId,
        long endSegmentId,
        long startTimestamp,
        long endTimestamp,
        long rawActionSliceExclusiveTimestamp,
        long stmSliceStartTimestamp,
        long stmSliceExclusiveTimestamp) {

    public MemorySourceMaterialWindow {
        if (startSegmentId <= 0L) {
            throw new IllegalArgumentException("startSegmentId must be > 0");
        }
        if (endSegmentId <= 0L) {
            throw new IllegalArgumentException("endSegmentId must be > 0");
        }
        if (startTimestamp < 0L) {
            throw new IllegalArgumentException("startTimestamp must be >= 0");
        }
        if (endTimestamp < startTimestamp) {
            throw new IllegalArgumentException("endTimestamp must be >= startTimestamp");
        }
        if (rawActionSliceExclusiveTimestamp < startTimestamp) {
            throw new IllegalArgumentException("rawActionSliceExclusiveTimestamp must be >= startTimestamp");
        }
        if (stmSliceStartTimestamp < 0L) {
            throw new IllegalArgumentException("stmSliceStartTimestamp must be >= 0");
        }
        if (stmSliceExclusiveTimestamp < stmSliceStartTimestamp) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= stmSliceStartTimestamp");
        }
    }

    public static MemorySourceMaterialWindow forEpisodeGateInput(EpisodeSynthesisGateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        EpisodeRegion region = input.region();
        if (region == null) {
            throw new IllegalArgumentException("gate input region must not be null");
        }
        return new MemorySourceMaterialWindow(
                region.startSegmentId(),
                region.endSegmentId(),
                region.startTimestamp(),
                region.endTimestamp(),
                exclusiveUpperBoundAfter(region.endTimestamp()),
                input.stmSliceStartTimestamp(),
                input.stmSliceExclusiveTimestamp());
    }

    public static MemorySourceMaterialWindow forEpisodeSignal(EpisodeSynthesisTriggerSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        return new MemorySourceMaterialWindow(
                signal.regionStartSegmentId(),
                signal.regionEndSegmentId(),
                signal.regionStartTimestamp(),
                signal.regionEndTimestamp(),
                exclusiveUpperBoundAfter(signal.regionEndTimestamp()),
                signal.stmSliceStartTimestamp(),
                signal.stmSliceExclusiveTimestamp());
    }

    private static long exclusiveUpperBoundAfter(long inclusiveTimestamp) {
        long safeInclusive = Math.max(0L, inclusiveTimestamp);
        return safeInclusive == Long.MAX_VALUE ? Long.MAX_VALUE : safeInclusive + 1L;
    }
}
