package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

/**
 * 已确认的 episode 分割点。
 */
public record EpisodeIntentSplitPoint(
        int splitSegmentIndex,
        long splitTimestamp,
        int confirmationSegmentIndex,
        long confirmationTimestamp,
        EpisodeBoundaryReason reason,
        PlayerIntent previousPrimaryIntent,
        PlayerIntent previousSecondaryIntent,
        PlayerIntent nextPrimaryIntent,
        PlayerIntent nextSecondaryIntent) {
}
