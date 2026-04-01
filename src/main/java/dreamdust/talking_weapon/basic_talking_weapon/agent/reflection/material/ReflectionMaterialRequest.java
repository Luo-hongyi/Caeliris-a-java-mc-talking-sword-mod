package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemorySourceMaterialWindow;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;

/**
 * Reflection task 对 material 的读取请求。
 */
public record ReflectionMaterialRequest(
        ReflectionScope scope,
        long regionId,
        long startSegmentId,
        long endSegmentId,
        long leftBoundaryLeftSegmentId,
        long leftBoundaryRightSegmentId,
        long rightBoundaryLeftSegmentId,
        long rightBoundaryRightSegmentId,
        long startTimestamp,
        long endTimestamp,
        long stmSliceStartTimestamp,
        long stmSliceExclusiveTimestamp) {

    public ReflectionMaterialRequest {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (regionId <= 0L) {
            throw new IllegalArgumentException("regionId must be > 0");
        }
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
        if (stmSliceStartTimestamp < 0L) {
            throw new IllegalArgumentException("stmSliceStartTimestamp must be >= 0");
        }
        if (stmSliceExclusiveTimestamp < stmSliceStartTimestamp) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= stmSliceStartTimestamp");
        }
    }

    public static ReflectionMaterialRequest forEpisodeSynthesisSignal(EpisodeSynthesisTriggerSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        return new ReflectionMaterialRequest(
                ReflectionScope.REGION,
                signal.regionId(),
                signal.regionStartSegmentId(),
                signal.regionEndSegmentId(),
                signal.leftBoundaryLeftSegmentId(),
                signal.leftBoundaryRightSegmentId(),
                signal.rightBoundaryLeftSegmentId(),
                signal.rightBoundaryRightSegmentId(),
                signal.regionStartTimestamp(),
                signal.regionEndTimestamp(),
                signal.stmSliceStartTimestamp(),
                signal.stmSliceExclusiveTimestamp());
    }

    public MemorySourceMaterialWindow toSourceMaterialWindow() {
        return new MemorySourceMaterialWindow(
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                exclusiveUpperBoundAfter(endTimestamp),
                stmSliceStartTimestamp,
                stmSliceExclusiveTimestamp);
    }

    private static long exclusiveUpperBoundAfter(long inclusiveTimestamp) {
        long safeInclusive = Math.max(0L, inclusiveTimestamp);
        return safeInclusive == Long.MAX_VALUE ? Long.MAX_VALUE : safeInclusive + 1L;
    }
}
