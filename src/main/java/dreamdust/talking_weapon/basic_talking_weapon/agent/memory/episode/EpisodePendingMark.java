package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

/**
 * Episode v2 中等待 confirm 的候选边界。
 * <p>
 * pending mark 与最终 split point 的位置保持完全一致；
 * 区别只在于它尚未被某个 splitter confirm。
 */
public record EpisodePendingMark(
        long detectedAtSegmentId,
        long splitLeftSegmentId,
        long splitRightSegmentId,
        long splitTimestamp,
        EpisodeSplitReason reason,
        long confirmAtSegmentId,
        String ownerSplitterId,
        boolean highPriority,
        int coverBeforeSegments,
        int coverAfterSegments) {

    public EpisodePendingMark {
        if (detectedAtSegmentId <= 0L) {
            throw new IllegalArgumentException("detectedAtSegmentId must be > 0");
        }
        if (splitLeftSegmentId <= 0L && splitRightSegmentId <= 0L) {
            throw new IllegalArgumentException("split boundary must reference at least one segment id");
        }
        if (confirmAtSegmentId < detectedAtSegmentId) {
            throw new IllegalArgumentException("confirmAtSegmentId must be >= detectedAtSegmentId");
        }
        ownerSplitterId = ownerSplitterId == null ? "" : ownerSplitterId.trim();
        if (ownerSplitterId.isEmpty()) {
            throw new IllegalArgumentException("ownerSplitterId must not be blank");
        }
        coverBeforeSegments = Math.max(0, coverBeforeSegments);
        coverAfterSegments = Math.max(0, coverAfterSegments);
    }

    public static EpisodePendingMark fromRuntimeConfig(
            long detectedAtSegmentId,
            long splitLeftSegmentId,
            long splitRightSegmentId,
            long splitTimestamp,
            EpisodeSplitReason reason,
            String ownerSplitterId) {
        EpisodeReasonRuntimeConfig config = EpisodeRuntimeSettings.getConfig(reason);
        long materializedConfirmFloor = splitRightSegmentId > 0L
                ? splitRightSegmentId
                : detectedAtSegmentId + 1L;
        long confirmAtSegmentId = Math.max(
                materializedConfirmFloor,
                detectedAtSegmentId + config.confirmDelaySegments());
        return new EpisodePendingMark(
                detectedAtSegmentId,
                splitLeftSegmentId,
                splitRightSegmentId,
                splitTimestamp,
                reason,
                confirmAtSegmentId,
                ownerSplitterId,
                config.highPriority(),
                config.coverBeforeSegments(),
                config.coverAfterSegments());
    }

    public boolean isHighPriority() {
        return highPriority;
    }

    public long splitAnchorSegmentId() {
        if (splitRightSegmentId > 0L) {
            return splitRightSegmentId;
        }
        if (splitLeftSegmentId > 0L) {
            return splitLeftSegmentId + 1L;
        }
        return EpisodeTimeline.UNASSIGNED_SEGMENT_ID;
    }

    public boolean coversSplitAnchor(long candidateSplitAnchorSegmentId) {
        long anchorSegmentId = splitAnchorSegmentId();
        if (anchorSegmentId <= 0L || candidateSplitAnchorSegmentId <= 0L) {
            return false;
        }
        long min = Math.max(1L, anchorSegmentId - coverBeforeSegments);
        long max = anchorSegmentId + coverAfterSegments;
        return candidateSplitAnchorSegmentId >= min && candidateSplitAnchorSegmentId <= max;
    }

    public boolean sameKey(EpisodePendingMark other) {
        if (other == null) {
            return false;
        }
        return splitAnchorSegmentId() == other.splitAnchorSegmentId()
                && confirmAtSegmentId == other.confirmAtSegmentId
                && reason == other.reason
                && ownerSplitterId.equals(other.ownerSplitterId);
    }
}
