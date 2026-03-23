package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 第一版基于玩家 intent 的 episode 分割跟踪器。
 * <p>
 * 当前只做分钟级 intent region 聚合与延迟确认的 split，
 * 不直接修改 STMPool，也不接入地点/结构等辅助断点。
 */
public final class EpisodeIntentTracker {

    public static final double SECONDARY_INTENT_RATIO = 0.72;
    public static final int MIN_REGION_SEGMENTS_BEFORE_SHIFT = 2;
    public static final int HARD_SHIFT_CONFIRM_SEGMENTS = 1;
    public static final int SOFT_SHIFT_CONFIRM_SEGMENTS = 1;
    public static final double DECISIVE_SHIFT_DOMINANCE_RATIO = 1.35;

    private EpisodeIntentTracker() {
    }

    public static EpisodeIntentTimeline analyzeSegments(List<ActionCompactedSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new EpisodeIntentTimeline(0, List.of(), List.of());
        }
        return analyze(PlayerIntentScorer.analyze(segments));
    }

    public static EpisodeIntentTimeline analyze(List<PlayerIntentSegmentAnalysis> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return new EpisodeIntentTimeline(0, List.of(), List.of());
        }

        List<EpisodeIntentRegion> regions = new ArrayList<>();
        List<EpisodeIntentSplitPoint> splitPoints = new ArrayList<>();

        RegionBuilder current = new RegionBuilder(0);
        current.add(analyses.get(0));

        PendingShift pendingShift = null;

        for (int i = 1; i < analyses.size(); i++) {
            PlayerIntentSegmentAnalysis analysis = analyses.get(i);
            if (analysis == null || analysis.getSegment() == null) {
                continue;
            }

            if (pendingShift == null && current.segmentCount() < MIN_REGION_SEGMENTS_BEFORE_SHIFT) {
                current.add(analysis);
                continue;
            }

            ShiftType shiftType = classifyShift(current, analysis);
            if (pendingShift == null) {
                if (shiftType == ShiftType.STABLE) {
                    current.add(analysis);
                    continue;
                }
                pendingShift = new PendingShift(i, shiftType);
                pendingShift.add(analysis);
                if (pendingShift.confirmThreshold() <= 1) {
                    current = confirmPendingShift(regions, splitPoints, current, pendingShift, i, analysis);
                    pendingShift = null;
                }
                continue;
            }

            if (shiftType == ShiftType.STABLE) {
                current.addAll(pendingShift.analyses);
                pendingShift = null;
                current.add(analysis);
                continue;
            }

            pendingShift.escalate(shiftType);
            pendingShift.add(analysis);

            if (pendingShift.size() < pendingShift.confirmThreshold()) {
                continue;
            }

            current = confirmPendingShift(regions, splitPoints, current, pendingShift, i, analysis);
            pendingShift = null;
        }

        if (pendingShift != null) {
            current.addAll(pendingShift.analyses);
        }

        regions.add(current.freeze());
        return new EpisodeIntentTimeline(analyses.size(), regions, splitPoints);
    }

    private static RegionBuilder confirmPendingShift(
            List<EpisodeIntentRegion> regions,
            List<EpisodeIntentSplitPoint> splitPoints,
            RegionBuilder current,
            PendingShift pendingShift,
            int confirmationSegmentIndex,
            PlayerIntentSegmentAnalysis confirmationAnalysis) {
        EpisodeIntentRegion previousRegion = current.freeze();
        regions.add(previousRegion);

        RegionBuilder nextRegion = new RegionBuilder(pendingShift.startSegmentIndex);
        nextRegion.addAll(pendingShift.analyses);
        EpisodeIntentRegion nextPreview = nextRegion.freeze();

        long confirmationTimestamp = nextPreview.startTimestamp();
        if (confirmationAnalysis != null && confirmationAnalysis.getSegment() != null) {
            confirmationTimestamp = Math.max(
                    confirmationTimestamp,
                    confirmationAnalysis.getSegment().getEndTimestamp());
        }

        splitPoints.add(new EpisodeIntentSplitPoint(
                pendingShift.startSegmentIndex,
                nextPreview.startTimestamp(),
                confirmationSegmentIndex,
                confirmationTimestamp,
                pendingShift.toBoundaryReason(),
                previousRegion.primaryIntent(),
                previousRegion.secondaryIntent(),
                nextPreview.primaryIntent(),
                nextPreview.secondaryIntent()));

        return nextRegion;
    }

    private static ShiftType classifyShift(RegionBuilder current, PlayerIntentSegmentAnalysis analysis) {
        if (current == null || analysis == null || !analysis.hasSignal()) {
            return ShiftType.STABLE;
        }

        IntentProfile regionProfile = deriveProfile(current.aggregateScores);
        IntentProfile segmentProfile = deriveProfile(analysis.getScores());
        if (regionProfile.primaryIntent == null || segmentProfile.primaryIntent == null) {
            return ShiftType.STABLE;
        }

        if (regionProfile.activeIntents.contains(segmentProfile.primaryIntent)) {
            return ShiftType.STABLE;
        }

        EnumSet<PlayerIntent> overlap = EnumSet.copyOf(segmentProfile.activeIntents);
        overlap.retainAll(regionProfile.activeIntents);
        if (overlap.isEmpty()) {
            return ShiftType.HARD;
        }

        double decisiveThreshold = Math.max(
                0.01,
                segmentProfile.secondaryScore <= 0.0
                        ? 0.0
                        : segmentProfile.secondaryScore * DECISIVE_SHIFT_DOMINANCE_RATIO);
        if (segmentProfile.primaryScore > decisiveThreshold) {
            return ShiftType.HARD;
        }
        return ShiftType.SOFT;
    }

    private static IntentProfile deriveProfile(Map<PlayerIntent, Double> scores) {
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

        return new IntentProfile(primary, primaryScore, secondary, secondaryScore, activeIntents);
    }

    private enum ShiftType {
        STABLE,
        SOFT,
        HARD
    }

    private record IntentProfile(
            PlayerIntent primaryIntent,
            double primaryScore,
            PlayerIntent secondaryIntent,
            double secondaryScore,
            EnumSet<PlayerIntent> activeIntents) {
    }

    private static final class RegionBuilder {
        private final int startSegmentIndex;
        private int endSegmentIndex;
        private int segmentCount;
        private long startTimestamp = -1L;
        private long endTimestamp = -1L;
        private final EnumMap<PlayerIntent, Double> aggregateScores = new EnumMap<>(PlayerIntent.class);

        private RegionBuilder(int startSegmentIndex) {
            this.startSegmentIndex = startSegmentIndex;
            this.endSegmentIndex = startSegmentIndex - 1;
            this.segmentCount = 0;
            for (PlayerIntent intent : PlayerIntent.values()) {
                aggregateScores.put(intent, 0.0);
            }
        }

        private void addAll(List<PlayerIntentSegmentAnalysis> analyses) {
            if (analyses == null || analyses.isEmpty()) {
                return;
            }
            for (PlayerIntentSegmentAnalysis analysis : analyses) {
                add(analysis);
            }
        }

        private void add(PlayerIntentSegmentAnalysis analysis) {
            if (analysis == null || analysis.getSegment() == null) {
                return;
            }

            ActionCompactedSegment segment = analysis.getSegment();
            if (startTimestamp < 0L) {
                startTimestamp = segment.getStartTimestamp();
            }
            endTimestamp = Math.max(endTimestamp, segment.getEndTimestamp());
            segmentCount++;
            endSegmentIndex = startSegmentIndex + segmentCount - 1;

            for (PlayerIntent intent : PlayerIntent.values()) {
                aggregateScores.put(intent, aggregateScores.get(intent) + analysis.getScore(intent));
            }
        }

        private int segmentCount() {
            return segmentCount;
        }

        private EpisodeIntentRegion freeze() {
            IntentProfile profile = deriveProfile(aggregateScores);
            int effectiveEndIndex = Math.max(startSegmentIndex, endSegmentIndex);
            return new EpisodeIntentRegion(
                    startSegmentIndex,
                    effectiveEndIndex,
                    Math.max(0L, startTimestamp),
                    Math.max(Math.max(0L, startTimestamp), endTimestamp),
                    aggregateScores,
                    profile.primaryIntent,
                    profile.primaryScore,
                    profile.secondaryIntent,
                    profile.secondaryScore);
        }
    }

    private static final class PendingShift {
        private final int startSegmentIndex;
        private ShiftType shiftType;
        private final List<PlayerIntentSegmentAnalysis> analyses = new ArrayList<>();

        private PendingShift(int startSegmentIndex, ShiftType shiftType) {
            this.startSegmentIndex = startSegmentIndex;
            this.shiftType = shiftType;
        }

        private void add(PlayerIntentSegmentAnalysis analysis) {
            if (analysis != null) {
                analyses.add(analysis);
            }
        }

        private int size() {
            return analyses.size();
        }

        private void escalate(ShiftType nextType) {
            if (nextType == ShiftType.HARD) {
                this.shiftType = ShiftType.HARD;
            } else if (nextType == ShiftType.SOFT && this.shiftType == ShiftType.STABLE) {
                this.shiftType = ShiftType.SOFT;
            }
        }

        private int confirmThreshold() {
            return shiftType == ShiftType.HARD
                    ? HARD_SHIFT_CONFIRM_SEGMENTS
                    : SOFT_SHIFT_CONFIRM_SEGMENTS;
        }

        private EpisodeBoundaryReason toBoundaryReason() {
            return shiftType == ShiftType.HARD
                    ? EpisodeBoundaryReason.INTENT_HARD_SHIFT
                    : EpisodeBoundaryReason.INTENT_SOFT_SHIFT_CONFIRMED;
        }
    }
}
