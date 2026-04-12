package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

/**
 * episode 封口后立即发出的 recent state update 信号。
 * <p>
 * 这是只读 reflection 任务的输入，不会消费 source material。
 */
public record AgentRecentStateUpdateTriggerSignal(
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
        long stmSliceExclusiveTimestamp,
        String segmentClosePrimaryEmotion,
        Integer segmentCloseEmotionIntensity,
        Integer segmentCloseFavorability,
        String episodeClosePlayerProfile,
        String episodeCloseRecap) {

    public AgentRecentStateUpdateTriggerSignal {
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
        segmentClosePrimaryEmotion = segmentClosePrimaryEmotion == null ? "" : segmentClosePrimaryEmotion.trim();
        episodeClosePlayerProfile = episodeClosePlayerProfile == null ? "" : episodeClosePlayerProfile.trim();
        episodeCloseRecap = episodeCloseRecap == null ? "" : episodeCloseRecap.trim();
    }
}
