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
        int leftBoundarySplitSegmentIndex,
        int regionStartSegmentIndex,
        int regionEndSegmentIndex,
        int rightBoundarySplitSegmentIndex,
        long regionStartTimestamp,
        long regionEndTimestamp,
        long stmSliceExclusiveTimestamp) {

    public EpisodeSynthesisTriggerSignal {
        if (leftBoundarySplitSegmentIndex < 0) {
            throw new IllegalArgumentException("leftBoundarySplitSegmentIndex must be >= 0");
        }
        if (regionStartSegmentIndex < 0) {
            throw new IllegalArgumentException("regionStartSegmentIndex must be >= 0");
        }
        if (regionEndSegmentIndex < regionStartSegmentIndex) {
            throw new IllegalArgumentException("regionEndSegmentIndex must be >= regionStartSegmentIndex");
        }
        if (rightBoundarySplitSegmentIndex <= regionEndSegmentIndex) {
            throw new IllegalArgumentException("rightBoundarySplitSegmentIndex must be after regionEndSegmentIndex");
        }
        if (stmSliceExclusiveTimestamp < 0L) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= 0");
        }
    }
}
