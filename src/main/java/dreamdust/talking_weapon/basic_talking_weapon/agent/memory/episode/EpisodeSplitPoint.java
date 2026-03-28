package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

/**
 * Episode v2 中的正式分割点。
 */
public record EpisodeSplitPoint(
        int splitTimelineIndex,
        long splitLeftSegmentId,
        long splitRightSegmentId,
        long splitTimestamp,
        int confirmationTimelineIndex,
        long confirmationSegmentId,
        long confirmationTimestamp,
        EpisodeSplitReason reason,
        PlayerIntent previousPrimaryIntent,
        PlayerIntent previousSecondaryIntent,
        PlayerIntent nextPrimaryIntent,
        PlayerIntent nextSecondaryIntent) {

    public EpisodeSplitPoint {
        if (splitTimelineIndex < 0) {
            throw new IllegalArgumentException("splitTimelineIndex must be >= 0");
        }
        if (confirmationTimelineIndex < 0) {
            throw new IllegalArgumentException("confirmationTimelineIndex must be >= 0");
        }
    }

    public boolean sameAnchor(EpisodeSplitPoint other) {
        if (other == null) {
            return false;
        }
        boolean hasStableBoundaryIds = splitLeftSegmentId > 0L || splitRightSegmentId > 0L;
        boolean otherHasStableBoundaryIds = other.splitLeftSegmentId > 0L || other.splitRightSegmentId > 0L;
        if (hasStableBoundaryIds && otherHasStableBoundaryIds) {
            return splitLeftSegmentId == other.splitLeftSegmentId
                    && splitRightSegmentId == other.splitRightSegmentId
                    && splitTimestamp == other.splitTimestamp
                    && reason == other.reason;
        }
        return splitTimelineIndex == other.splitTimelineIndex
                && splitTimestamp == other.splitTimestamp
                && reason == other.reason;
    }
}
