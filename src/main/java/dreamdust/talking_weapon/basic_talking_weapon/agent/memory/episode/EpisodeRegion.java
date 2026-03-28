package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 一个 confirmed split 之间的连续 episode region。
 * <p>
 * 当前 region 自身就是正式状态载体，
 * 后续会随 episode timeline 一起持久化到 workspace runtime state 文件。
 */
public record EpisodeRegion(
        int startSegmentIndex,
        int endSegmentIndex,
        long startSegmentId,
        long endSegmentId,
        long startTimestamp,
        long endTimestamp,
        Map<PlayerIntent, Double> aggregateIntentScores,
        PlayerIntent primaryIntent,
        double primaryScore,
        PlayerIntent secondaryIntent,
        double secondaryScore,
        long regionId,
        EpisodeRegionGateDecision gateDecision,
        EpisodeRegionLifecycleState lifecycleState) {

    public EpisodeRegion(
            int startSegmentIndex,
            int endSegmentIndex,
            long startTimestamp,
            long endTimestamp,
            Map<PlayerIntent, Double> aggregateIntentScores,
            PlayerIntent primaryIntent,
            double primaryScore,
            PlayerIntent secondaryIntent,
            double secondaryScore) {
        this(
                startSegmentIndex,
                endSegmentIndex,
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore);
    }

    public EpisodeRegion(
            int startSegmentIndex,
            int endSegmentIndex,
            long startSegmentId,
            long endSegmentId,
            long startTimestamp,
            long endTimestamp,
            Map<PlayerIntent, Double> aggregateIntentScores,
            PlayerIntent primaryIntent,
            double primaryScore,
            PlayerIntent secondaryIntent,
            double secondaryScore) {
        this(
                startSegmentIndex,
                endSegmentIndex,
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                deriveRegionId(startSegmentId, endSegmentId, startTimestamp, endTimestamp, startSegmentIndex, endSegmentIndex),
                EpisodeRegionGateDecision.UNSET,
                EpisodeRegionLifecycleState.ACTIVE);
    }

    public EpisodeRegion(
            int startSegmentIndex,
            int endSegmentIndex,
            long startTimestamp,
            long endTimestamp,
            Map<PlayerIntent, Double> aggregateIntentScores,
            PlayerIntent primaryIntent,
            double primaryScore,
            PlayerIntent secondaryIntent,
            double secondaryScore,
            EpisodeRegionGateDecision gateDecision,
            EpisodeRegionLifecycleState lifecycleState) {
        this(
                startSegmentIndex,
                endSegmentIndex,
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                gateDecision,
                lifecycleState);
    }

    public EpisodeRegion(
            int startSegmentIndex,
            int endSegmentIndex,
            long startSegmentId,
            long endSegmentId,
            long startTimestamp,
            long endTimestamp,
            Map<PlayerIntent, Double> aggregateIntentScores,
            PlayerIntent primaryIntent,
            double primaryScore,
            PlayerIntent secondaryIntent,
            double secondaryScore,
            EpisodeRegionGateDecision gateDecision,
            EpisodeRegionLifecycleState lifecycleState) {
        this(
                startSegmentIndex,
                endSegmentIndex,
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                deriveRegionId(startSegmentId, endSegmentId, startTimestamp, endTimestamp, startSegmentIndex, endSegmentIndex),
                gateDecision,
                lifecycleState);
    }

    public EpisodeRegion {
        EnumMap<PlayerIntent, Double> copy = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            double score = aggregateIntentScores == null ? 0.0 : aggregateIntentScores.getOrDefault(intent, 0.0);
            copy.put(intent, score);
        }
        aggregateIntentScores = Collections.unmodifiableMap(copy);
        if (regionId <= 0L) {
            regionId = deriveRegionId(startSegmentId, endSegmentId, startTimestamp, endTimestamp, startSegmentIndex, endSegmentIndex);
        }
        if (gateDecision == null) {
            gateDecision = EpisodeRegionGateDecision.UNSET;
        }
        if (lifecycleState == null) {
            lifecycleState = EpisodeRegionLifecycleState.ACTIVE;
        }
    }

    public int segmentCount() {
        return Math.max(0, endSegmentIndex - startSegmentIndex + 1);
    }

    public boolean hasIntentSignal() {
        return primaryIntent != null && primaryScore > 0.0;
    }

    public double getScore(PlayerIntent intent) {
        if (intent == null) {
            return 0.0;
        }
        return aggregateIntentScores.getOrDefault(intent, 0.0);
    }

    public EnumSet<PlayerIntent> getActiveIntents() {
        EnumSet<PlayerIntent> intents = EnumSet.noneOf(PlayerIntent.class);
        if (primaryIntent != null) {
            intents.add(primaryIntent);
        }
        if (secondaryIntent != null && secondaryScore > 0.0) {
            intents.add(secondaryIntent);
        }
        return intents;
    }

    public EpisodeRegion withRuntimeState(
            EpisodeRegionGateDecision nextGateDecision,
            EpisodeRegionLifecycleState nextLifecycleState) {
        return new EpisodeRegion(
                startSegmentIndex,
                endSegmentIndex,
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                regionId,
                nextGateDecision,
                nextLifecycleState);
    }

    private static long deriveRegionId(
            long startSegmentId,
            long endSegmentId,
            long startTimestamp,
            long endTimestamp,
            int startSegmentIndex,
            int endSegmentIndex) {
        long hash = 1469598103934665603L;
        if (startSegmentId > 0L || endSegmentId > 0L) {
            hash = mix(hash, startSegmentId);
            hash = mix(hash, endSegmentId);
        }
        hash = mix(hash, startTimestamp);
        hash = mix(hash, endTimestamp);
        hash = mix(hash, startSegmentIndex);
        hash = mix(hash, endSegmentIndex);
        return hash == 0L ? 1L : hash;
    }

    private static long mix(long current, long value) {
        long mixed = current ^ value;
        return mixed * 1099511628211L;
    }
}
