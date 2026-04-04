package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

/**
 * Episode v2 中的正式分割点。
 */
public record EpisodeSplitPoint(
        long splitLeftSegmentId,
        long splitRightSegmentId,
        long splitTimestamp,
        long confirmationSegmentId,
        long confirmationTimestamp,
        EpisodeSplitReason reason,
        PlayerIntent previousPrimaryIntent,
        PlayerIntent previousSecondaryIntent,
        PlayerIntent nextPrimaryIntent,
        PlayerIntent nextSecondaryIntent) {

    public EpisodeSplitPoint {
        if (splitLeftSegmentId <= 0L && splitRightSegmentId <= 0L) {
            throw new IllegalArgumentException("split boundary must reference at least one segment id");
        }
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

    public boolean sameAnchor(EpisodeSplitPoint other) {
        if (other == null) {
            return false;
        }
        return splitAnchorSegmentId() == other.splitAnchorSegmentId()
                && splitTimestamp == other.splitTimestamp
                && reason == other.reason;
    }
}
