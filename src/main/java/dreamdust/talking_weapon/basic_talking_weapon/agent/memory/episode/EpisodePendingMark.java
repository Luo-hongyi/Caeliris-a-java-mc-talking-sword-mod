package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

/**
 * Episode v2 中等待 confirm 的候选边界。
 * <p>
 * pending mark 与最终 split point 的位置保持完全一致；
 * 区别只在于它尚未被某个 splitter confirm。
 */
public record EpisodePendingMark(
        int detectedAtSegmentIndex,
        long detectedAtSegmentId,
        int splitSegmentIndex,
        long splitLeftSegmentId,
        long splitRightSegmentId,
        long splitTimestamp,
        EpisodeSplitReason reason,
        int confirmAtSegmentIndex,
        String ownerSplitterId,
        boolean highPriority,
        int coverBeforeSegments,
        int coverAfterSegments) {

    public EpisodePendingMark {
        if (detectedAtSegmentIndex < 0) {
            throw new IllegalArgumentException("detectedAtSegmentIndex must be >= 0");
        }
        if (splitSegmentIndex < 0) {
            throw new IllegalArgumentException("splitSegmentIndex must be >= 0");
        }
        if (confirmAtSegmentIndex < splitSegmentIndex) {
            throw new IllegalArgumentException("confirmAtSegmentIndex must be >= splitSegmentIndex");
        }
        ownerSplitterId = ownerSplitterId == null ? "" : ownerSplitterId.trim();
        if (ownerSplitterId.isEmpty()) {
            throw new IllegalArgumentException("ownerSplitterId must not be blank");
        }
        coverBeforeSegments = Math.max(0, coverBeforeSegments);
        coverAfterSegments = Math.max(0, coverAfterSegments);
    }

    public static EpisodePendingMark fromRuntimeConfig(
            EpisodeTimeline timeline,
            int detectedAtSegmentIndex,
            long detectedAtSegmentId,
            int splitSegmentIndex,
            long splitTimestamp,
            EpisodeSplitReason reason,
            String ownerSplitterId) {
        EpisodeReasonRuntimeConfig config = EpisodeRuntimeSettings.getConfig(reason);
        int confirmAtSegmentIndex = Math.max(
                splitSegmentIndex,
                detectedAtSegmentIndex + config.confirmDelaySegments());
        EpisodeTimeline.BoundarySegmentIds boundarySegmentIds = timeline == null
                ? new EpisodeTimeline.BoundarySegmentIds(
                        EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                        EpisodeTimeline.UNASSIGNED_SEGMENT_ID)
                : timeline.boundarySegmentIdsAt(splitSegmentIndex);
        return new EpisodePendingMark(
                detectedAtSegmentIndex,
                detectedAtSegmentId,
                splitSegmentIndex,
                boundarySegmentIds.leftSegmentId(),
                boundarySegmentIds.rightSegmentId(),
                splitTimestamp,
                reason,
                confirmAtSegmentIndex,
                ownerSplitterId,
                config.highPriority(),
                config.coverBeforeSegments(),
                config.coverAfterSegments());
    }

    public static EpisodePendingMark fromRuntimeConfig(
            int detectedAtSegmentIndex,
            int splitSegmentIndex,
            long splitTimestamp,
            EpisodeSplitReason reason,
            String ownerSplitterId) {
        return fromRuntimeConfig(
                EpisodeTimeline.empty(),
                detectedAtSegmentIndex,
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                splitSegmentIndex,
                splitTimestamp,
                reason,
                ownerSplitterId);
    }

    public boolean isHighPriority() {
        return highPriority;
    }

    public boolean coversSplitIndex(int candidateSplitIndex) {
        int min = splitSegmentIndex - coverBeforeSegments;
        int max = splitSegmentIndex + coverAfterSegments;
        return candidateSplitIndex >= min && candidateSplitIndex <= max;
    }

    public boolean sameKey(EpisodePendingMark other) {
        if (other == null) {
            return false;
        }
        boolean hasStableBoundaryIds = splitLeftSegmentId > 0L || splitRightSegmentId > 0L;
        boolean otherHasStableBoundaryIds = other.splitLeftSegmentId > 0L || other.splitRightSegmentId > 0L;
        if (hasStableBoundaryIds && otherHasStableBoundaryIds) {
            return splitLeftSegmentId == other.splitLeftSegmentId
                    && splitRightSegmentId == other.splitRightSegmentId
                    && confirmAtSegmentIndex == other.confirmAtSegmentIndex
                    && reason == other.reason
                    && ownerSplitterId.equals(other.ownerSplitterId);
        }
        return splitSegmentIndex == other.splitSegmentIndex
                && confirmAtSegmentIndex == other.confirmAtSegmentIndex
                && reason == other.reason
                && ownerSplitterId.equals(other.ownerSplitterId);
    }
}
