package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeDetectContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 observed action type 的通用边界 splitter。
 * <p>
 * 适用于 enter / leave 这类已经由 watcher 产出为正式动作的强边界信号。
 */
public final class ObservedActionTypeSplitter implements EpisodeSplitter {

    private final String id;
    private final ActionType actionType;
    private final EpisodeSplitReason reason;
    private final boolean splitAfterCurrentSegment;

    public ObservedActionTypeSplitter(
            String id,
            ActionType actionType,
            EpisodeSplitReason reason,
            boolean splitAfterCurrentSegment) {
        this.id = Objects.requireNonNull(id, "id");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.splitAfterCurrentSegment = splitAfterCurrentSegment;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<EpisodePendingMark> detect(EpisodeDetectContext context) {
        FixedActionSegment latestSegment = context == null ? null : context.latestSegment();
        int latestSegmentIndex = context == null ? -1 : context.latestSegmentIndex();
        if (latestSegment == null
                || latestSegment.getSplitterSignals() == null
                || !latestSegment.getSplitterSignals().hasObservedAction(actionType)) {
            return List.of();
        }

        int splitSegmentIndex = splitAfterCurrentSegment
                ? latestSegmentIndex + 1
                : latestSegmentIndex;
        long splitTimestamp = splitAfterCurrentSegment
                ? latestSegment.getEndTimestamp()
                : latestSegment.getStartTimestamp();
        return List.of(EpisodePendingMark.fromRuntimeConfig(
                latestSegmentIndex,
                splitSegmentIndex,
                splitTimestamp,
                reason,
                id()));
    }

    @Override
    public boolean supports(EpisodePendingMark pendingMark) {
        return pendingMark != null && pendingMark.reason() == reason;
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
