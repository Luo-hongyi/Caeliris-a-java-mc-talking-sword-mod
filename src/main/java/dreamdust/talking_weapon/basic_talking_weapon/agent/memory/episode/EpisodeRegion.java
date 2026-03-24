package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 一个 confirmed split 之间的连续 episode region。
 */
public record EpisodeRegion(
        int startSegmentIndex,
        int endSegmentIndex,
        long startTimestamp,
        long endTimestamp,
        Map<PlayerIntent, Double> aggregateIntentScores,
        PlayerIntent primaryIntent,
        double primaryScore,
        PlayerIntent secondaryIntent,
        double secondaryScore) {

    public EpisodeRegion {
        EnumMap<PlayerIntent, Double> copy = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            double score = aggregateIntentScores == null ? 0.0 : aggregateIntentScores.getOrDefault(intent, 0.0);
            copy.put(intent, score);
        }
        aggregateIntentScores = Collections.unmodifiableMap(copy);
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
}
