package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 单个动作时间段上的 intent 打分结果。
 * <p>
 * 这是可复用的纯信号对象，不再绑定某个外部分析载体。
 */
public final class SegmentIntentSignals {

    private static final SegmentIntentSignals EMPTY = new SegmentIntentSignals(
            Map.of(),
            Map.of(),
            null,
            0.0,
            null,
            0.0,
            false);

    private final Map<PlayerIntent, Double> scores;
    private final Map<PlayerIntent, List<String>> reasons;
    private final PlayerIntent dominantIntent;
    private final double dominantScore;
    private final PlayerIntent runnerUpIntent;
    private final double runnerUpScore;
    private final boolean ambiguous;

    public SegmentIntentSignals(
            Map<PlayerIntent, Double> scores,
            Map<PlayerIntent, List<String>> reasons,
            PlayerIntent dominantIntent,
            double dominantScore,
            PlayerIntent runnerUpIntent,
            double runnerUpScore,
            boolean ambiguous) {
        EnumMap<PlayerIntent, Double> scoreCopy = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            double score = scores == null ? 0.0 : scores.getOrDefault(intent, 0.0);
            scoreCopy.put(intent, score);
        }
        this.scores = Collections.unmodifiableMap(scoreCopy);

        EnumMap<PlayerIntent, List<String>> reasonCopy = new EnumMap<>(PlayerIntent.class);
        for (PlayerIntent intent : PlayerIntent.values()) {
            List<String> raw = reasons == null ? List.of() : reasons.get(intent);
            if (raw == null || raw.isEmpty()) {
                reasonCopy.put(intent, List.of());
            } else {
                reasonCopy.put(intent, Collections.unmodifiableList(new ArrayList<>(raw)));
            }
        }
        this.reasons = Collections.unmodifiableMap(reasonCopy);

        this.dominantIntent = dominantIntent;
        this.dominantScore = dominantScore;
        this.runnerUpIntent = runnerUpIntent;
        this.runnerUpScore = runnerUpScore;
        this.ambiguous = ambiguous;
    }

    public static SegmentIntentSignals empty() {
        return EMPTY;
    }

    public Map<PlayerIntent, Double> getScores() {
        return scores;
    }

    public double getScore(PlayerIntent intent) {
        if (intent == null) {
            return 0.0;
        }
        return scores.getOrDefault(intent, 0.0);
    }

    public Map<PlayerIntent, List<String>> getReasons() {
        return reasons;
    }

    public List<String> getReasons(PlayerIntent intent) {
        if (intent == null) {
            return List.of();
        }
        return reasons.getOrDefault(intent, List.of());
    }

    public PlayerIntent getDominantIntent() {
        return dominantIntent;
    }

    public double getDominantScore() {
        return dominantScore;
    }

    public PlayerIntent getRunnerUpIntent() {
        return runnerUpIntent;
    }

    public double getRunnerUpScore() {
        return runnerUpScore;
    }

    public boolean isAmbiguous() {
        return ambiguous;
    }

    public boolean hasSignal() {
        return dominantIntent != null && dominantScore > 0.0;
    }

    public List<PlayerIntent> getRankedIntents() {
        List<PlayerIntent> ranked = new ArrayList<>(List.of(PlayerIntent.values()));
        ranked.sort(Comparator
                .comparingDouble((PlayerIntent intent) -> getScore(intent))
                .reversed()
                .thenComparing(Enum::name));
        return Collections.unmodifiableList(ranked);
    }
}
