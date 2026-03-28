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
        int leftBoundarySplitTimelineIndex,
        long leftBoundaryLeftSegmentId,
        long leftBoundaryRightSegmentId,
        int regionStartTimelineIndex,
        long regionStartSegmentId,
        int regionEndTimelineIndex,
        long regionEndSegmentId,
        int rightBoundarySplitTimelineIndex,
        long rightBoundaryLeftSegmentId,
        long rightBoundaryRightSegmentId,
        long regionStartTimestamp,
        long regionEndTimestamp,
        long stmSliceExclusiveTimestamp) {

    public EpisodeSynthesisTriggerSignal {
        if (regionId <= 0L) {
            throw new IllegalArgumentException("regionId must be > 0");
        }
        if (leftBoundarySplitTimelineIndex < 0) {
            throw new IllegalArgumentException("leftBoundarySplitTimelineIndex must be >= 0");
        }
        if (regionStartTimelineIndex < 0) {
            throw new IllegalArgumentException("regionStartTimelineIndex must be >= 0");
        }
        if (regionEndTimelineIndex < regionStartTimelineIndex) {
            throw new IllegalArgumentException("regionEndTimelineIndex must be >= regionStartTimelineIndex");
        }
        if (rightBoundarySplitTimelineIndex <= regionEndTimelineIndex) {
            throw new IllegalArgumentException("rightBoundarySplitTimelineIndex must be after regionEndTimelineIndex");
        }
        if (stmSliceExclusiveTimestamp < 0L) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= 0");
        }
    }
}
