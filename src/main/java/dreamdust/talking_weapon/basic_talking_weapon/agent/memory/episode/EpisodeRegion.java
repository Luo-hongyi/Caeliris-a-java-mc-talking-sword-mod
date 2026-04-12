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
 * <p>
 * 正式时间范围语义固定为 `[startTimestamp, endTimestamp)`。
 */
public record EpisodeRegion(
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
        EpisodeRegionLifecycleState lifecycleState,
        EpisodeRecentStateUpdateState recentStateUpdateState,
        EpisodeRegionStateSnapshot closeStateSnapshot) {

    public EpisodeRegion(
            long startTimestamp,
            long endTimestamp,
            Map<PlayerIntent, Double> aggregateIntentScores,
            PlayerIntent primaryIntent,
            double primaryScore,
            PlayerIntent secondaryIntent,
            double secondaryScore) {
        this(
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
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                deriveRegionId(startSegmentId, endSegmentId, startTimestamp, endTimestamp),
                EpisodeRegionGateDecision.UNSET,
                EpisodeRegionLifecycleState.ACTIVE,
                EpisodeRecentStateUpdateState.UNSET,
                EpisodeRegionStateSnapshot.empty());
    }

    public EpisodeRegion(
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
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                gateDecision,
                lifecycleState,
                EpisodeRecentStateUpdateState.UNSET,
                EpisodeRegionStateSnapshot.empty());
    }

    public EpisodeRegion(
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
                lifecycleState,
                EpisodeRecentStateUpdateState.UNSET,
                EpisodeRegionStateSnapshot.empty());
    }

    public EpisodeRegion(
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
            EpisodeRegionLifecycleState lifecycleState,
            EpisodeRecentStateUpdateState recentStateUpdateState,
            EpisodeRegionStateSnapshot closeStateSnapshot) {
        this(
                startSegmentId,
                endSegmentId,
                startTimestamp,
                endTimestamp,
                aggregateIntentScores,
                primaryIntent,
                primaryScore,
                secondaryIntent,
                secondaryScore,
                deriveRegionId(startSegmentId, endSegmentId, startTimestamp, endTimestamp),
                gateDecision,
                lifecycleState,
                recentStateUpdateState,
                closeStateSnapshot);
    }

    public EpisodeRegion {
        EnumMap<PlayerIntent, Double> copy = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            double score = aggregateIntentScores == null ? 0.0 : aggregateIntentScores.getOrDefault(intent, 0.0);
            copy.put(intent, score);
        }
        aggregateIntentScores = Collections.unmodifiableMap(copy);
        if (regionId <= 0L) {
            regionId = deriveRegionId(startSegmentId, endSegmentId, startTimestamp, endTimestamp);
        }
        if (gateDecision == null) {
            gateDecision = EpisodeRegionGateDecision.UNSET;
        }
        if (lifecycleState == null) {
            lifecycleState = EpisodeRegionLifecycleState.ACTIVE;
        }
        if (recentStateUpdateState == null) {
            recentStateUpdateState = EpisodeRecentStateUpdateState.UNSET;
        }
        if (closeStateSnapshot == null) {
            closeStateSnapshot = EpisodeRegionStateSnapshot.empty();
        }
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
                nextLifecycleState,
                recentStateUpdateState,
                closeStateSnapshot);
    }

    public EpisodeRegion withRecentStateUpdateState(EpisodeRecentStateUpdateState nextRecentStateUpdateState) {
        return new EpisodeRegion(
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
                gateDecision,
                lifecycleState,
                nextRecentStateUpdateState,
                closeStateSnapshot);
    }

    public EpisodeRegion withCloseStateSnapshot(EpisodeRegionStateSnapshot nextCloseStateSnapshot) {
        return new EpisodeRegion(
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
                gateDecision,
                lifecycleState,
                recentStateUpdateState,
                nextCloseStateSnapshot);
    }

    private static long deriveRegionId(
            long startSegmentId,
            long endSegmentId,
            long startTimestamp,
            long endTimestamp) {
        long hash = 1469598103934665603L;
        if (startSegmentId > 0L || endSegmentId > 0L) {
            hash = mix(hash, startSegmentId);
            hash = mix(hash, endSegmentId);
        }
        hash = mix(hash, startTimestamp);
        hash = mix(hash, endTimestamp);
        return hash == 0L ? 1L : hash;
    }

    private static long mix(long current, long value) {
        long mixed = current ^ value;
        return mixed * 1099511628211L;
    }
}
