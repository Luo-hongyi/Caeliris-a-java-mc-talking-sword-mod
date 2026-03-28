package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

/**
 * fixed segment 正式 discard window。
 * <p>
 * 第一版先保持扁平：只表达一段 sealed segment range 当前应从 active view 中被过滤掉。
 */
public record FixedSegmentDiscardWindow(
        long startSegmentId,
        long endSegmentId,
        long startTimestamp,
        long endTimestamp,
        long createdAt) {

    public FixedSegmentDiscardWindow {
        if (startSegmentId <= 0L) {
            throw new IllegalArgumentException("startSegmentId must be > 0");
        }
        if (endSegmentId <= 0L) {
            throw new IllegalArgumentException("endSegmentId must be > 0");
        }
        if (endSegmentId < startSegmentId) {
            throw new IllegalArgumentException("endSegmentId must be >= startSegmentId");
        }
        if (startTimestamp < 0L) {
            throw new IllegalArgumentException("startTimestamp must be >= 0");
        }
        if (endTimestamp < startTimestamp) {
            throw new IllegalArgumentException("endTimestamp must be >= startTimestamp");
        }
        createdAt = Math.max(0L, createdAt);
    }

    public boolean sameRange(FixedSegmentDiscardWindow other) {
        if (other == null) {
            return false;
        }
        return startSegmentId == other.startSegmentId
                && endSegmentId == other.endSegmentId;
    }
}
