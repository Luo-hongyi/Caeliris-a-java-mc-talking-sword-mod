package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

/**
 * Episode v2 中的正式分割点。
 */
public record EpisodeSplitPoint(
        int splitSegmentIndex,
        long splitTimestamp,
        int confirmationSegmentIndex,
        long confirmationTimestamp,
        EpisodeSplitReason reason,
        PlayerIntent previousPrimaryIntent,
        PlayerIntent previousSecondaryIntent,
        PlayerIntent nextPrimaryIntent,
        PlayerIntent nextSecondaryIntent) {

    public EpisodeSplitPoint {
        if (splitSegmentIndex < 0) {
            throw new IllegalArgumentException("splitSegmentIndex must be >= 0");
        }
        if (confirmationSegmentIndex < 0) {
            throw new IllegalArgumentException("confirmationSegmentIndex must be >= 0");
        }
    }

    public boolean sameAnchor(EpisodeSplitPoint other) {
        if (other == null) {
            return false;
        }
        return splitSegmentIndex == other.splitSegmentIndex
                && splitTimestamp == other.splitTimestamp
                && reason == other.reason;
    }
}
