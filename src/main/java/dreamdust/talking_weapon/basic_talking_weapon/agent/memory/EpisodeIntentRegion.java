package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 一段连续 episode region 的聚合视图。
 */
public record EpisodeIntentRegion(
        int startSegmentIndex,
        int endSegmentIndex,
        long startTimestamp,
        long endTimestamp,
        Map<PlayerIntent, Double> aggregateScores,
        PlayerIntent primaryIntent,
        double primaryScore,
        PlayerIntent secondaryIntent,
        double secondaryScore) {

    public EpisodeIntentRegion {
        EnumMap<PlayerIntent, Double> copy = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            double score = aggregateScores == null ? 0.0 : aggregateScores.getOrDefault(intent, 0.0);
            copy.put(intent, score);
        }
        aggregateScores = Collections.unmodifiableMap(copy);
    }

    public int segmentCount() {
        return Math.max(0, endSegmentIndex - startSegmentIndex + 1);
    }

    public boolean hasSignal() {
        return primaryIntent != null && primaryScore > 0.0;
    }

    public double getScore(PlayerIntent intent) {
        if (intent == null) {
            return 0.0;
        }
        return aggregateScores.getOrDefault(intent, 0.0);
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
}
