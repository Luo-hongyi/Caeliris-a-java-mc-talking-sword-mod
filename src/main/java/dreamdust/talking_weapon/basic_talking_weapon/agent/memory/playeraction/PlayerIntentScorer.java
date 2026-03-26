package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.PlayerIntent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第一版基于规则的玩家意图打分器。
 * <p>
 * 当前按“单个固定时间段”独立打分，不做跨段平滑。
 * 输入是 {@link ActionArchiveCompactor} 的压缩结果，
 * 输出是每段的多意图分数、主导意图和可读理由。
 */
public final class PlayerIntentScorer {

    private static final double AMBIGUOUS_RATIO = 0.82;

    private static final double GATHER_USE_GATHER_TOOL_WEIGHT = 3.0;
    private static final double GATHER_MINE_BLOCK_WEIGHT = 1.0;
    private static final double GATHER_ACQUIRE_ITEM_WEIGHT = 1.4;

    private static final double TRAVEL_WEIGHT = 4.0;
    private static final double BIOME_CHANGE_WEIGHT = 1.4;
    private static final double DIM_CHANGE_WEIGHT = 4.5;
    private static final double REGISTERED_LOCATION_ENTER_TRAVEL_WEIGHT = 1.1;
    private static final double REGISTERED_LOCATION_LEAVE_TRAVEL_WEIGHT = 1.6;
    private static final double MAJOR_STRUCTURE_ENTER_TRAVEL_WEIGHT = 0.9;
    private static final double MAJOR_STRUCTURE_EXIT_TRAVEL_WEIGHT = 1.4;

    private static final double EXPLORE_LOOT_CONTAINER_WEIGHT = 5.0;
    private static final double EXPLORE_TRAVEL_SUPPORT = 1.0;
    private static final double EXPLORE_BIOME_SUPPORT = 0.8;
    private static final double EXPLORE_DIM_SUPPORT = 1.6;
    private static final double MAJOR_STRUCTURE_ENTER_EXPLORE_WEIGHT = 2.1;
    private static final double MAJOR_STRUCTURE_EXIT_EXPLORE_WEIGHT = 0.8;

    private static final double ATTACK_WEIGHT = 2.2;
    private static final double KILL_WEIGHT = 4.0;
    private static final double HURT_BY_WEIGHT = 2.8;
    private static final double DEATH_WEIGHT = 5.0;

    private static final double BUILD_PLACE_BLOCK_WEIGHT = 2.3;

    private static final double INTERACT_FUNCTION_BLOCK_WEIGHT = 2.2;
    private static final double CRAFT_ITEM_WEIGHT = 3.0;

    private static final double INTERACT_NPC_WEIGHT = 2.4;
    private static final double TRADE_WEIGHT = 4.0;

    private static final double GATHER_RESULT_SYNERGY = 1.0;
    private static final double TRAVEL_CONTEXT_SYNERGY = 0.8;
    private static final double EXPLORE_LOOT_TRAVEL_SYNERGY = 1.3;
    private static final double EXPLORE_LOOT_COMBAT_SYNERGY = 0.8;
    private static final double COMBAT_OVERLAP_SYNERGY = 1.0;
    private static final double COMBAT_STATIONARY_BONUS = 0.8;
    private static final double BUILD_STATIONARY_BONUS = 0.6;
    private static final double CRAFT_CHAIN_SYNERGY = 1.2;
    private static final double SOCIAL_TRADE_SYNERGY = 1.2;

    private PlayerIntentScorer() {
    }

    public static List<SegmentIntentSignals> analyze(List<? extends ActionSegmentView> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<SegmentIntentSignals> analyses = new ArrayList<>(segments.size());
        for (ActionSegmentView segment : segments) {
            analyses.add(analyze(segment));
        }
        return List.copyOf(analyses);
    }

    public static SegmentIntentSignals analyze(ActionSegmentView segment) {
        ScoreAccumulator acc = new ScoreAccumulator();
        if (segment == null || segment.getEntries().isEmpty()) {
            return acc.finish();
        }

        Map<ActionType, Integer> countByType = buildCountByType(segment);
        Map<String, Integer> gatherCountByTarget = buildCountByTarget(segment, ActionType.USE_GATHER_TOOL);

        scoreGather(segment, acc, gatherCountByTarget);
        scoreTravel(countByType, acc);
        scoreExplore(countByType, acc);
        scoreCombat(countByType, acc);
        scoreBuild(countByType, acc);
        scoreCraft(countByType, acc);
        scoreSocial(countByType, acc);

        applySynergy(countByType, acc);
        return acc.finish();
    }

    private static void scoreGather(
            ActionSegmentView segment,
            ScoreAccumulator acc,
            Map<String, Integer> gatherCountByTarget) {
        int totalGatherToolCount = 0;
        int totalAcquireItemCount = 0;

        for (ActionSegmentEntryView entry : segment.getEntries()) {
            if (entry.getType() == ActionType.USE_GATHER_TOOL) {
                totalGatherToolCount += entry.getCount();
                acc.add(
                        PlayerIntent.GATHER,
                        GATHER_USE_GATHER_TOOL_WEIGHT * weightedCount(entry.getCount()),
                        "%s x%d -> gather tool",
                        entry.getType(),
                        entry.getCount());
            } else if (entry.getType() == ActionType.MINE_BLOCK) {
                int coveredCount = gatherCountByTarget.getOrDefault(normalizeTarget(entry.getTarget()), 0);
                int uncoveredCount = Math.max(0, entry.getCount() - coveredCount);
                if (uncoveredCount > 0) {
                    acc.add(
                            PlayerIntent.GATHER,
                            GATHER_MINE_BLOCK_WEIGHT * weightedCount(uncoveredCount),
                            "%s x%d -> uncovered mining",
                            entry.getType(),
                            uncoveredCount);
                } else {
                    acc.note(
                            PlayerIntent.GATHER,
                            "%s x%d -> fully covered by USE_GATHER_TOOL",
                            entry.getType(),
                            entry.getCount());
                }
            } else if (entry.getType() == ActionType.ACQUIRE_ITEM) {
                totalAcquireItemCount += entry.getCount();
                acc.add(
                        PlayerIntent.GATHER,
                        GATHER_ACQUIRE_ITEM_WEIGHT * weightedCount(entry.getCount()),
                        "%s x%d -> pickup result",
                        entry.getType(),
                        entry.getCount());
            }
        }

        if (totalGatherToolCount > 0 && totalAcquireItemCount > 0) {
            acc.add(
                    PlayerIntent.GATHER,
                    GATHER_RESULT_SYNERGY,
                    "gather result synergy -> tool use + pickup");
        }
    }

    private static void scoreTravel(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        addTypeScore(acc, PlayerIntent.TRAVEL, countByType, ActionType.TRAVEL, TRAVEL_WEIGHT, "travel anchor");
        addTypeScore(acc, PlayerIntent.TRAVEL, countByType, ActionType.BIOME_CHANGE, BIOME_CHANGE_WEIGHT, "biome shift");
        addTypeScore(acc, PlayerIntent.TRAVEL, countByType, ActionType.DIM_CHANGE, DIM_CHANGE_WEIGHT, "dimension shift");
        addTypeScore(
                acc,
                PlayerIntent.TRAVEL,
                countByType,
                ActionType.ENTER_REGISTERED_LOCATION,
                REGISTERED_LOCATION_ENTER_TRAVEL_WEIGHT,
                "registered location arrival");
        addTypeScore(
                acc,
                PlayerIntent.TRAVEL,
                countByType,
                ActionType.LEAVE_REGISTERED_LOCATION,
                REGISTERED_LOCATION_LEAVE_TRAVEL_WEIGHT,
                "registered location departure");
        addTypeScore(
                acc,
                PlayerIntent.TRAVEL,
                countByType,
                ActionType.ENTER_MAJOR_STRUCTURE,
                MAJOR_STRUCTURE_ENTER_TRAVEL_WEIGHT,
                "major structure arrival");
        addTypeScore(
                acc,
                PlayerIntent.TRAVEL,
                countByType,
                ActionType.EXIT_MAJOR_STRUCTURE,
                MAJOR_STRUCTURE_EXIT_TRAVEL_WEIGHT,
                "major structure departure");
    }

    private static void scoreExplore(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        addTypeScore(
                acc,
                PlayerIntent.EXPLORE,
                countByType,
                ActionType.OPEN_LOOT_CONTAINER,
                EXPLORE_LOOT_CONTAINER_WEIGHT,
                "opened loot container");
        addTypeScore(
                acc,
                PlayerIntent.EXPLORE,
                countByType,
                ActionType.TRAVEL,
                EXPLORE_TRAVEL_SUPPORT,
                "exploration travel support");
        addTypeScore(
                acc,
                PlayerIntent.EXPLORE,
                countByType,
                ActionType.BIOME_CHANGE,
                EXPLORE_BIOME_SUPPORT,
                "exploration biome support");
        addTypeScore(
                acc,
                PlayerIntent.EXPLORE,
                countByType,
                ActionType.DIM_CHANGE,
                EXPLORE_DIM_SUPPORT,
                "exploration dimension support");
        addTypeScore(
                acc,
                PlayerIntent.EXPLORE,
                countByType,
                ActionType.ENTER_MAJOR_STRUCTURE,
                MAJOR_STRUCTURE_ENTER_EXPLORE_WEIGHT,
                "entered major structure");
        addTypeScore(
                acc,
                PlayerIntent.EXPLORE,
                countByType,
                ActionType.EXIT_MAJOR_STRUCTURE,
                MAJOR_STRUCTURE_EXIT_EXPLORE_WEIGHT,
                "left major structure");
    }

    private static void scoreCombat(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        addTypeScore(acc, PlayerIntent.COMBAT, countByType, ActionType.ATTACK, ATTACK_WEIGHT, "attack pressure");
        addTypeScore(acc, PlayerIntent.COMBAT, countByType, ActionType.KILL, KILL_WEIGHT, "kill confirmation");
        addTypeScore(acc, PlayerIntent.COMBAT, countByType, ActionType.HURT_BY, HURT_BY_WEIGHT, "under threat");
        addTypeScore(acc, PlayerIntent.COMBAT, countByType, ActionType.DEATH, DEATH_WEIGHT, "death spike");
    }

    private static void scoreBuild(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        addTypeScore(acc, PlayerIntent.BUILD, countByType, ActionType.PLACE_BLOCK, BUILD_PLACE_BLOCK_WEIGHT, "block placement");
    }

    private static void scoreCraft(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        addTypeScore(
                acc,
                PlayerIntent.CRAFT,
                countByType,
                ActionType.INTERACT_FUNCTION_BLOCK,
                INTERACT_FUNCTION_BLOCK_WEIGHT,
                "function block interaction");
        addTypeScore(acc, PlayerIntent.CRAFT, countByType, ActionType.CRAFT_ITEM, CRAFT_ITEM_WEIGHT, "crafted output");
    }

    private static void scoreSocial(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        addTypeScore(acc, PlayerIntent.SOCIAL, countByType, ActionType.INTERACT_NPC, INTERACT_NPC_WEIGHT, "npc interaction");
        addTypeScore(acc, PlayerIntent.SOCIAL, countByType, ActionType.TRADE, TRADE_WEIGHT, "trade completion");
    }

    private static void applySynergy(Map<ActionType, Integer> countByType, ScoreAccumulator acc) {
        int biomeChangeCount = countByType.getOrDefault(ActionType.BIOME_CHANGE, 0);
        int dimChangeCount = countByType.getOrDefault(ActionType.DIM_CHANGE, 0);
        int travelCount = countByType.getOrDefault(ActionType.TRAVEL, 0);
        int lootContainerCount = countByType.getOrDefault(ActionType.OPEN_LOOT_CONTAINER, 0);
        if (travelCount > 0 && (biomeChangeCount > 0 || dimChangeCount > 0)) {
            double synergy = TRAVEL_CONTEXT_SYNERGY * ((biomeChangeCount > 0 ? 1.0 : 0.0) + (dimChangeCount > 0 ? 1.0 : 0.0));
            acc.add(PlayerIntent.TRAVEL, synergy, "travel context synergy");
        }

        if (lootContainerCount > 0 && (travelCount > 0 || biomeChangeCount > 0 || dimChangeCount > 0)) {
            double travelSupportCount =
                    (travelCount > 0 ? 1.0 : 0.0)
                    + (biomeChangeCount > 0 ? 1.0 : 0.0)
                    + (dimChangeCount > 0 ? 1.0 : 0.0);
            acc.add(
                    PlayerIntent.EXPLORE,
                    EXPLORE_LOOT_TRAVEL_SYNERGY * travelSupportCount,
                    "loot exploration synergy -> container + travel context");
        }

        int combatSignalTypes = presentTypeCount(
                countByType,
                ActionType.ATTACK,
                ActionType.KILL,
                ActionType.HURT_BY,
                ActionType.DEATH);
        if (combatSignalTypes >= 2) {
            acc.add(
                    PlayerIntent.COMBAT,
                    COMBAT_OVERLAP_SYNERGY * (combatSignalTypes - 1),
                    "combat overlap synergy -> %d signal types",
                    combatSignalTypes);
        }
        if (combatSignalTypes > 0 && travelCount <= 0) {
            acc.add(PlayerIntent.COMBAT, COMBAT_STATIONARY_BONUS, "stationary combat bonus");
        }
        if (lootContainerCount > 0 && combatSignalTypes > 0) {
            acc.add(
                    PlayerIntent.EXPLORE,
                    EXPLORE_LOOT_COMBAT_SYNERGY * combatSignalTypes,
                    "loot exploration combat synergy -> %d combat signal types",
                    combatSignalTypes);
        }

        int placeBlockCount = countByType.getOrDefault(ActionType.PLACE_BLOCK, 0);
        if (placeBlockCount > 0 && travelCount <= 0) {
            acc.add(PlayerIntent.BUILD, BUILD_STATIONARY_BONUS, "stationary build bonus");
        }

        int interactFunctionBlockCount = countByType.getOrDefault(ActionType.INTERACT_FUNCTION_BLOCK, 0);
        int craftItemCount = countByType.getOrDefault(ActionType.CRAFT_ITEM, 0);
        if (interactFunctionBlockCount > 0 && craftItemCount > 0) {
            acc.add(PlayerIntent.CRAFT, CRAFT_CHAIN_SYNERGY, "craft chain synergy");
        }

        int interactNpcCount = countByType.getOrDefault(ActionType.INTERACT_NPC, 0);
        int tradeCount = countByType.getOrDefault(ActionType.TRADE, 0);
        if (interactNpcCount > 0 && tradeCount > 0) {
            acc.add(PlayerIntent.SOCIAL, SOCIAL_TRADE_SYNERGY, "social trade synergy");
        }
    }

    private static void addTypeScore(
            ScoreAccumulator acc,
            PlayerIntent intent,
            Map<ActionType, Integer> countByType,
            ActionType type,
            double baseWeight,
            String label) {
        int count = countByType.getOrDefault(type, 0);
        if (count <= 0) {
            return;
        }
        acc.add(intent, baseWeight * weightedCount(count), "%s x%d -> %s", type, count, label);
    }

    private static Map<ActionType, Integer> buildCountByType(ActionSegmentView segment) {
        Map<ActionType, Integer> countByType = new LinkedHashMap<>();
        for (ActionSegmentEntryView entry : segment.getEntries()) {
            countByType.merge(entry.getType(), entry.getCount(), Integer::sum);
        }
        return countByType;
    }

    private static Map<String, Integer> buildCountByTarget(ActionSegmentView segment, ActionType targetType) {
        Map<String, Integer> countByTarget = new LinkedHashMap<>();
        for (ActionSegmentEntryView entry : segment.getEntries()) {
            if (entry.getType() != targetType) {
                continue;
            }
            countByTarget.merge(normalizeTarget(entry.getTarget()), entry.getCount(), Integer::sum);
        }
        return countByTarget;
    }

    private static String normalizeTarget(String target) {
        return target == null ? "" : target;
    }

    private static int presentTypeCount(Map<ActionType, Integer> countByType, ActionType... types) {
        int count = 0;
        for (ActionType type : types) {
            if (countByType.getOrDefault(type, 0) > 0) {
                count++;
            }
        }
        return count;
    }

    private static double weightedCount(int count) {
        return Math.sqrt(Math.max(1, count));
    }

    private static final class ScoreAccumulator {
        private final EnumMap<PlayerIntent, Double> scores = new EnumMap<>(PlayerIntent.class);
        private final EnumMap<PlayerIntent, List<String>> reasons = new EnumMap<>(PlayerIntent.class);

        private ScoreAccumulator() {
            for (PlayerIntent intent : PlayerIntent.values()) {
                scores.put(intent, 0.0);
                reasons.put(intent, new ArrayList<>());
            }
        }

        private void add(PlayerIntent intent, double score, String reasonTemplate, Object... reasonArgs) {
            if (intent == null || score <= 0.0) {
                return;
            }
            scores.put(intent, scores.get(intent) + score);
            reasons.get(intent).add(formatReason(score, reasonTemplate, reasonArgs));
        }

        private void note(PlayerIntent intent, String reasonTemplate, Object... reasonArgs) {
            if (intent == null) {
                return;
            }
            reasons.get(intent).add(String.format(Locale.ROOT, reasonTemplate, reasonArgs));
        }

        private SegmentIntentSignals finish() {
            PlayerIntent dominantIntent = null;
            double dominantScore = 0.0;
            PlayerIntent runnerUpIntent = null;
            double runnerUpScore = 0.0;

            for (PlayerIntent intent : PlayerIntent.values()) {
                double score = scores.get(intent);
                if (score > dominantScore) {
                    runnerUpIntent = dominantIntent;
                    runnerUpScore = dominantScore;
                    dominantIntent = intent;
                    dominantScore = score;
                } else if (score > runnerUpScore) {
                    runnerUpIntent = intent;
                    runnerUpScore = score;
                }
            }

            if (dominantScore <= 0.0) {
                dominantIntent = null;
                runnerUpIntent = null;
                runnerUpScore = 0.0;
            }

            boolean ambiguous = dominantIntent != null
                    && runnerUpIntent != null
                    && runnerUpScore > 0.0
                    && runnerUpScore >= dominantScore * AMBIGUOUS_RATIO;

            return new SegmentIntentSignals(
                    scores,
                    reasons,
                    dominantIntent,
                    dominantScore,
                    runnerUpIntent,
                    runnerUpScore,
                    ambiguous);
        }

        private static String formatReason(double score, String reasonTemplate, Object... reasonArgs) {
            return String.format(
                    Locale.ROOT,
                    "%s -> +%.2f",
                    String.format(Locale.ROOT, reasonTemplate, reasonArgs),
                    score);
        }
    }
}
