package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.SegmentIntentSignals;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public final class EpisodeIntentMath {

    static final double SECONDARY_INTENT_RATIO = 0.72;

    private EpisodeIntentMath() {
    }

    static SegmentIntentSignals intentSignals(FixedActionSegment segment) {
        if (segment == null || segment.getSplitterSignals() == null) {
            return SegmentIntentSignals.empty();
        }
        return segment.getSplitterSignals().getIntentSignals();
    }

    static EnumMap<PlayerIntent, Double> aggregateScores(List<FixedActionSegment> segments, int startInclusive, int endInclusive) {
        EnumMap<PlayerIntent, Double> aggregate = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            aggregate.put(intent, 0.0);
        }
        if (segments == null || segments.isEmpty()) {
            return aggregate;
        }
        int safeStart = Math.max(0, startInclusive);
        int safeEnd = Math.min(endInclusive, segments.size() - 1);
        if (safeStart > safeEnd) {
            return aggregate;
        }

        for (int index = safeStart; index <= safeEnd; index++) {
            SegmentIntentSignals signals = intentSignals(segments.get(index));
            for (PlayerIntent intent : PlayerIntent.values()) {
                aggregate.put(intent, aggregate.get(intent) + signals.getScore(intent));
            }
        }
        return aggregate;
    }

    static EpisodeIntentProfile deriveProfile(Map<PlayerIntent, Double> scores) {
        PlayerIntent primary = null;
        double primaryScore = 0.0;
        PlayerIntent secondary = null;
        double secondaryScore = 0.0;

        for (PlayerIntent intent : PlayerIntent.values()) {
            double score = scores == null ? 0.0 : scores.getOrDefault(intent, 0.0);
            if (score > primaryScore) {
                secondary = primary;
                secondaryScore = primaryScore;
                primary = intent;
                primaryScore = score;
            } else if (score > secondaryScore) {
                secondary = intent;
                secondaryScore = score;
            }
        }

        if (primaryScore <= 0.0) {
            primary = null;
            secondary = null;
            secondaryScore = 0.0;
        } else if (secondary == null
                || secondaryScore <= 0.0
                || secondaryScore < primaryScore * SECONDARY_INTENT_RATIO) {
            secondary = null;
            secondaryScore = 0.0;
        }

        EnumSet<PlayerIntent> activeIntents = EnumSet.noneOf(PlayerIntent.class);
        if (primary != null) {
            activeIntents.add(primary);
        }
        if (secondary != null) {
            activeIntents.add(secondary);
        }

        return new EpisodeIntentProfile(primary, primaryScore, secondary, secondaryScore, activeIntents);
    }

    public static EpisodeRegion buildRegion(List<FixedActionSegment> segments, int startInclusive, int endInclusive) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        int safeStart = Math.max(0, startInclusive);
        int safeEnd = Math.min(endInclusive, segments.size() - 1);
        if (safeStart > safeEnd) {
            return null;
        }

        FixedActionSegment first = segments.get(safeStart);
        FixedActionSegment last = segments.get(safeEnd);
        EnumMap<PlayerIntent, Double> aggregateScores = aggregateScores(segments, safeStart, safeEnd);
        EpisodeIntentProfile profile = deriveProfile(aggregateScores);
        return new EpisodeRegion(
                safeStart,
                safeEnd,
                first != null ? first.getStartTimestamp() : 0L,
                last != null ? last.getEndTimestamp() : 0L,
                aggregateScores,
                profile.primaryIntent(),
                profile.primaryScore(),
                profile.secondaryIntent(),
                profile.secondaryScore());
    }
}
