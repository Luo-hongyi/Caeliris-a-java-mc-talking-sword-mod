package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeDetectContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeReasonRuntimeConfig;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.List;
import java.util.Optional;

/**
 * 唯一 high priority 的 v2 splitter。
 */
public final class DeathSplitter implements EpisodeSplitter {

    private static final String ID = "death";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<EpisodePendingMark> detect(EpisodeDetectContext context) {
        FixedActionSegment latestSegment = context == null ? null : context.latestSegment();
        int latestSegmentIndex = context == null ? -1 : context.latestSegmentIndex();
        if (latestSegment == null
                || latestSegment.getSplitterSignals() == null
                || !latestSegment.getSplitterSignals().hasObservedAction(ActionType.DEATH)) {
            return List.of();
        }

        EpisodeSplitReason reason = EpisodeSplitReason.DEATH;
        EpisodeReasonRuntimeConfig config = EpisodeRuntimeSettings.getConfig(reason);
        return List.of(EpisodePendingMark.fromRuntimeConfig(
                latestSegmentIndex,
                latestSegmentIndex + 1,
                latestSegment.getEndTimestamp(),
                reason,
                id()));
    }

    @Override
    public boolean supports(EpisodePendingMark pendingMark) {
        return pendingMark != null && pendingMark.reason() == EpisodeSplitReason.DEATH;
    }

    @Override
    public Optional<EpisodeSplitPoint> confirm(
            EpisodeTimeline timeline,
            EpisodePendingMark pendingMark,
            FixedActionSegment latestSegment,
            int latestSegmentIndex) {
        if (timeline == null || latestSegment == null || !supports(pendingMark)) {
            return Optional.empty();
        }

        EpisodeRegion previousRegion = timeline.regionBeforeSplit(pendingMark.splitSegmentIndex());
        EpisodeIntentProfile nextProfile = EpisodeIntentMath.deriveProfile(
                EpisodeIntentMath.aggregateScores(
                        timeline.segments(),
                        pendingMark.splitSegmentIndex(),
                        latestSegmentIndex));

        return Optional.of(new EpisodeSplitPoint(
                pendingMark.splitSegmentIndex(),
                pendingMark.splitTimestamp(),
                latestSegmentIndex,
                latestSegment.getEndTimestamp(),
                pendingMark.reason(),
                previousRegion != null ? previousRegion.primaryIntent() : null,
                previousRegion != null ? previousRegion.secondaryIntent() : null,
                nextProfile.primaryIntent(),
                nextProfile.secondaryIntent()));
    }
}
