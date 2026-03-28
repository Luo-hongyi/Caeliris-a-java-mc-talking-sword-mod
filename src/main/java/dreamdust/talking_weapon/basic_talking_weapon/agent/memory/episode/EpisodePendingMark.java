package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

/**
 * Episode v2 中等待 confirm 的候选边界。
 * <p>
 * pending mark 与最终 split point 的位置保持完全一致；
 * 区别只在于它尚未被某个 splitter confirm。
 */
public record EpisodePendingMark(
        int detectedAtTimelineIndex,
        long detectedAtSegmentId,
        int splitTimelineIndex,
        long splitLeftSegmentId,
        long splitRightSegmentId,
        long splitTimestamp,
        EpisodeSplitReason reason,
        int confirmAtTimelineIndex,
        String ownerSplitterId,
        boolean highPriority,
        int coverBeforeSegments,
        int coverAfterSegments) {

    public EpisodePendingMark {
        if (detectedAtTimelineIndex < 0) {
            throw new IllegalArgumentException("detectedAtTimelineIndex must be >= 0");
        }
        if (splitTimelineIndex < 0) {
            throw new IllegalArgumentException("splitTimelineIndex must be >= 0");
        }
        if (confirmAtTimelineIndex < splitTimelineIndex) {
            throw new IllegalArgumentException("confirmAtTimelineIndex must be >= splitTimelineIndex");
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
            int detectedAtTimelineIndex,
            long detectedAtSegmentId,
            int splitTimelineIndex,
            long splitTimestamp,
            EpisodeSplitReason reason,
            String ownerSplitterId) {
        EpisodeReasonRuntimeConfig config = EpisodeRuntimeSettings.getConfig(reason);
        int confirmAtTimelineIndex = Math.max(
                splitTimelineIndex,
                detectedAtTimelineIndex + config.confirmDelaySegments());
        EpisodeTimeline.BoundarySegmentIds boundarySegmentIds = timeline == null
                ? new EpisodeTimeline.BoundarySegmentIds(
                        EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                        EpisodeTimeline.UNASSIGNED_SEGMENT_ID)
                : timeline.boundarySegmentIdsAt(splitTimelineIndex);
        return new EpisodePendingMark(
                detectedAtTimelineIndex,
                detectedAtSegmentId,
                splitTimelineIndex,
                boundarySegmentIds.leftSegmentId(),
                boundarySegmentIds.rightSegmentId(),
                splitTimestamp,
                reason,
                confirmAtTimelineIndex,
                ownerSplitterId,
                config.highPriority(),
                config.coverBeforeSegments(),
                config.coverAfterSegments());
    }

    public static EpisodePendingMark fromRuntimeConfig(
            int detectedAtTimelineIndex,
            int splitTimelineIndex,
            long splitTimestamp,
            EpisodeSplitReason reason,
            String ownerSplitterId) {
        return fromRuntimeConfig(
                EpisodeTimeline.empty(),
                detectedAtTimelineIndex,
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                splitTimelineIndex,
                splitTimestamp,
                reason,
                ownerSplitterId);
    }

    public boolean isHighPriority() {
        return highPriority;
    }

    public boolean coversTimelineIndex(int candidateTimelineIndex) {
        int min = splitTimelineIndex - coverBeforeSegments;
        int max = splitTimelineIndex + coverAfterSegments;
        return candidateTimelineIndex >= min && candidateTimelineIndex <= max;
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
                    && confirmAtTimelineIndex == other.confirmAtTimelineIndex
                    && reason == other.reason
                    && ownerSplitterId.equals(other.ownerSplitterId);
        }
        return splitTimelineIndex == other.splitTimelineIndex
                && confirmAtTimelineIndex == other.confirmAtTimelineIndex
                && reason == other.reason
                && ownerSplitterId.equals(other.ownerSplitterId);
    }
}
