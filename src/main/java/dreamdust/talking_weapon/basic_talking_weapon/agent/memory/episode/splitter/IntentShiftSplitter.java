package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeDetectContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.SegmentIntentSignals;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * 统一处理 hard / soft intent shift 的 splitter。
 */
public final class IntentShiftSplitter implements EpisodeSplitter {

    private static final String ID = "intent_shift";
    private static final int MIN_REGION_SEGMENTS_BEFORE_SHIFT = 2;
    private static final double DECISIVE_SHIFT_DOMINANCE_RATIO = 1.35;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<EpisodePendingMark> detect(EpisodeDetectContext context) {
        EpisodeTimeline timeline = context == null ? null : context.timeline();
        FixedActionSegment latestSegment = context == null ? null : context.latestSegment();
        int latestTimelineIndex = context == null ? -1 : context.latestTimelineIndex();
        if (timeline == null || latestSegment == null) {
            return List.of();
        }

        EpisodeRegion currentRegion = timeline.latestRegion();
        if (currentRegion == null || currentRegion.segmentCount() < MIN_REGION_SEGMENTS_BEFORE_SHIFT) {
            return List.of();
        }

        SegmentIntentSignals latestSignals = EpisodeIntentMath.intentSignals(latestSegment);
        if (latestSignals == null || !latestSignals.hasSignal()) {
            return List.of();
        }

        EpisodeIntentProfile latestProfile = EpisodeIntentMath.deriveProfile(latestSignals.getScores());
        EpisodeSplitReason reason = classifyShift(currentRegion, latestProfile);
        if (reason == null) {
            return List.of();
        }

        return List.of(EpisodePendingMark.fromRuntimeConfig(
                timeline,
                latestTimelineIndex,
                latestSegment.getSegmentId(),
                latestTimelineIndex,
                latestSegment.getStartTimestamp(),
                reason,
                id()));
    }

    @Override
    public boolean supports(EpisodePendingMark pendingMark) {
        if (pendingMark == null) {
            return false;
        }
        return pendingMark.reason() == EpisodeSplitReason.INTENT_HARD_SHIFT
                || pendingMark.reason() == EpisodeSplitReason.INTENT_SOFT_SHIFT;
    }

    @Override
    public Optional<EpisodeSplitPoint> confirm(
            EpisodeTimeline timeline,
            EpisodePendingMark pendingMark,
            FixedActionSegment latestSegment,
            int latestTimelineIndex) {
        if (timeline == null || pendingMark == null || latestSegment == null || !supports(pendingMark)) {
            return Optional.empty();
        }

        EpisodeRegion previousRegion = timeline.regionBeforeSplit(pendingMark.splitTimelineIndex());
        if (previousRegion == null || previousRegion.segmentCount() < MIN_REGION_SEGMENTS_BEFORE_SHIFT) {
            return Optional.empty();
        }

        EpisodeIntentProfile nextProfile = EpisodeIntentMath.deriveProfile(
                EpisodeIntentMath.aggregateScores(
                        timeline.segments(),
                        pendingMark.splitTimelineIndex(),
                        latestTimelineIndex));
        EpisodeSplitReason confirmedReason = classifyShift(previousRegion, nextProfile);
        if (confirmedReason == null) {
            return Optional.empty();
        }
        if (pendingMark.reason() == EpisodeSplitReason.INTENT_HARD_SHIFT
                && confirmedReason != EpisodeSplitReason.INTENT_HARD_SHIFT) {
            return Optional.empty();
        }

        EpisodeTimeline.BoundarySegmentIds boundarySegmentIds =
                timeline.boundarySegmentIdsAt(pendingMark.splitTimelineIndex());

        return Optional.of(new EpisodeSplitPoint(
                pendingMark.splitTimelineIndex(),
                boundarySegmentIds.leftSegmentId(),
                boundarySegmentIds.rightSegmentId(),
                pendingMark.splitTimestamp(),
                latestTimelineIndex,
                latestSegment.getSegmentId(),
                latestSegment.getEndTimestamp(),
                confirmedReason,
                previousRegion.primaryIntent(),
                previousRegion.secondaryIntent(),
                nextProfile.primaryIntent(),
                nextProfile.secondaryIntent()));
    }

    private static EpisodeSplitReason classifyShift(EpisodeRegion previousRegion, EpisodeIntentProfile candidateProfile) {
        if (previousRegion == null || candidateProfile == null) {
            return null;
        }
        PlayerIntent primaryIntent = candidateProfile.primaryIntent();
        if (primaryIntent == null) {
            return null;
        }
        if (primaryIntent == previousRegion.primaryIntent()) {
            return null;
        }

        EnumSet<PlayerIntent> previousActive = previousRegion.getActiveIntents();
        if (previousActive.contains(primaryIntent)) {
            return EpisodeSplitReason.INTENT_SOFT_SHIFT;
        }

        EnumSet<PlayerIntent> overlap = EnumSet.copyOf(candidateProfile.activeIntents());
        overlap.retainAll(previousActive);
        if (overlap.isEmpty()) {
            return EpisodeSplitReason.INTENT_HARD_SHIFT;
        }

        double decisiveThreshold = Math.max(
                0.01,
                candidateProfile.secondaryScore() <= 0.0
                        ? 0.0
                        : candidateProfile.secondaryScore() * DECISIVE_SHIFT_DOMINANCE_RATIO);
        if (candidateProfile.primaryScore() > decisiveThreshold) {
            return EpisodeSplitReason.INTENT_HARD_SHIFT;
        }

        return EpisodeSplitReason.INTENT_SOFT_SHIFT;
    }
}
