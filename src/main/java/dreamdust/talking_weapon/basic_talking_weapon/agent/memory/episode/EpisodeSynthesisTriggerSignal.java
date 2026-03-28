package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

/**
 * Episode 对外的最小正式输出。
 * <p>
 * episode 子系统内部可以继续维护复杂的 segment / mark / split / region 状态，
 * 但对外部调度层只暴露一个“是否该触发 episode synthesis”的信号对象。
 * <p>
 * 后续“这个 episode 是否值得生成记忆”的判断，也应优先落在 episode 侧，
 * 由它决定是否产出该 signal。
 */
public record EpisodeSynthesisTriggerSignal(
        long regionId,
        long leftBoundaryLeftSegmentId,
        long leftBoundaryRightSegmentId,
        long regionStartSegmentId,
        long regionEndSegmentId,
        long rightBoundaryLeftSegmentId,
        long rightBoundaryRightSegmentId,
        long regionStartTimestamp,
        long regionEndTimestamp,
        long stmSliceStartTimestamp,
        long stmSliceExclusiveTimestamp) {

    public EpisodeSynthesisTriggerSignal {
        if (regionId <= 0L) {
            throw new IllegalArgumentException("regionId must be > 0");
        }
        if (regionStartSegmentId <= 0L) {
            throw new IllegalArgumentException("regionStartSegmentId must be > 0");
        }
        if (regionEndSegmentId <= 0L) {
            throw new IllegalArgumentException("regionEndSegmentId must be > 0");
        }
        if (regionStartTimestamp < 0L) {
            throw new IllegalArgumentException("regionStartTimestamp must be >= 0");
        }
        if (regionEndTimestamp < regionStartTimestamp) {
            throw new IllegalArgumentException("regionEndTimestamp must be >= regionStartTimestamp");
        }
        if (stmSliceStartTimestamp < 0L) {
            throw new IllegalArgumentException("stmSliceStartTimestamp must be >= 0");
        }
        if (stmSliceExclusiveTimestamp < stmSliceStartTimestamp) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= stmSliceStartTimestamp");
        }
    }
}
