package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.DeathSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeIntentMath;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.FallbackQuietSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.IntentShiftSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.ObservedActionTypeSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Episode v2 的统一调度器。
 * <p>
 * 它不承载具体规则细节，只负责：
 * <ul>
 * <li>喂 segment 给各个 splitter</li>
 * <li>收集 pending marks</li>
 * <li>强 mark 入场清理</li>
 * <li>到期 confirm</li>
 * <li>confirm 后清理</li>
 * <li>维护正式 regions</li>
 * </ul>
 */
public final class EpisodeTracker {

    private final List<EpisodeSplitter> splitters;
    private final ArrayList<FixedActionSegment> segments = new ArrayList<>();
    private final ArrayList<EpisodePendingMark> pendingMarks = new ArrayList<>();
    private final ArrayList<EpisodeSplitPoint> splitPoints = new ArrayList<>();
    private final ArrayList<EpisodeRegion> regions = new ArrayList<>();
    private final LinkedHashMap<Long, EpisodeRegion> seededRegionStates = new LinkedHashMap<>();
    private long lastAcceptedPendingDetectSegmentId = EpisodeTimeline.UNASSIGNED_SEGMENT_ID;

    public EpisodeTracker() {
        this(List.of(
                new IntentShiftSplitter(),
                new DeathSplitter(),
                new ObservedActionTypeSplitter(
                        "enter_registered_location",
                        ActionType.ENTER_REGISTERED_LOCATION,
                        EpisodeSplitReason.ENTER_REGISTERED_LOCATION,
                        false),
                new ObservedActionTypeSplitter(
                        "leave_registered_location",
                        ActionType.LEAVE_REGISTERED_LOCATION,
                        EpisodeSplitReason.LEAVE_REGISTERED_LOCATION,
                        false),
                new ObservedActionTypeSplitter(
                        "enter_major_structure",
                        ActionType.ENTER_MAJOR_STRUCTURE,
                        EpisodeSplitReason.ENTER_MAJOR_STRUCTURE,
                        false),
                new ObservedActionTypeSplitter(
                        "exit_major_structure",
                        ActionType.EXIT_MAJOR_STRUCTURE,
                        EpisodeSplitReason.EXIT_MAJOR_STRUCTURE,
                        false),
                new FallbackQuietSplitter()));
    }

    public EpisodeTracker(List<EpisodeSplitter> splitters) {
        this.splitters = splitters == null ? List.of() : List.copyOf(splitters);
    }

    public void reset() {
        segments.clear();
        pendingMarks.clear();
        splitPoints.clear();
        regions.clear();
        seededRegionStates.clear();
        lastAcceptedPendingDetectSegmentId = EpisodeTimeline.UNASSIGNED_SEGMENT_ID;
    }

    public EpisodeTimeline replaceSegments(List<FixedActionSegment> nextSegments) {
        return replaceSegments(nextSegments, List.of());
    }

    public EpisodeTimeline replaceSegments(
            List<FixedActionSegment> nextSegments,
            List<EpisodeRegion> regionStateSeeds) {
        reset();
        seedRegionStates(regionStateSeeds);
        appendSegments(nextSegments);
        return snapshotTimeline();
    }

    public EpisodeTimeline appendSegments(List<FixedActionSegment> nextSegments) {
        if (nextSegments == null || nextSegments.isEmpty()) {
            return snapshotTimeline();
        }
        for (FixedActionSegment segment : nextSegments) {
            appendSegment(segment);
        }
        return snapshotTimeline();
    }

    public EpisodeTimeline appendSegment(FixedActionSegment nextSegment) {
        if (nextSegment == null) {
            return snapshotTimeline();
        }

        boolean firstSegment = segments.isEmpty();
        EpisodeTimeline detectTimeline = snapshotTimeline();
        List<EpisodePendingMark> detectedMarks = detectPendingMarks(detectTimeline, nextSegment);

        segments.add(nextSegment);
        if (firstSegment) {
            seedBootstrapSplit(nextSegment);
        }
        addPendingMarks(detectedMarks);
        applyImmediateHighPriorityCleanup(detectedMarks);
        rebuildRegions();
        confirmExpiredMarks(nextSegment);
        rebuildRegions();
        return snapshotTimeline();
    }

    public EpisodeTimeline snapshotTimeline() {
        return new EpisodeTimeline(segments, regions, pendingMarks, splitPoints);
    }

    private List<EpisodePendingMark> detectPendingMarks(
            EpisodeTimeline timeline,
            FixedActionSegment latestSegment) {
        ArrayList<EpisodePendingMark> detected = new ArrayList<>();
        for (EpisodeSplitter splitter : splitters) {
            if (splitter == null) {
                continue;
            }
            EpisodeDetectContext detectContext = new EpisodeDetectContext(
                    timeline,
                    latestSegment,
                    detected,
                    lastAcceptedPendingDetectSegmentId);
            List<EpisodePendingMark> newMarks = splitter.detect(detectContext);
            if (newMarks == null || newMarks.isEmpty()) {
                continue;
            }
            for (EpisodePendingMark pendingMark : newMarks) {
                if (pendingMark != null) {
                    detected.add(pendingMark);
                }
            }
        }
        return detected;
    }

    private void addPendingMarks(List<EpisodePendingMark> detectedMarks) {
        if (detectedMarks == null || detectedMarks.isEmpty()) {
            return;
        }

        EpisodeTimeline currentTimeline = snapshotTimeline();
        long acceptedDetectSegmentId = lastAcceptedPendingDetectSegmentId;
        boolean acceptedAny = false;
        for (EpisodePendingMark pendingMark : detectedMarks) {
            if (pendingMark == null) {
                continue;
            }
            if (!isValidPendingMarkBoundary(currentTimeline, pendingMark)) {
                continue;
            }
            if (currentTimeline.hasPendingMark(pendingMark)) {
                continue;
            }
            if (hasSplitPointAtAnchor(pendingMark)) {
                continue;
            }
            pendingMarks.add(pendingMark);
            acceptedDetectSegmentId = Math.max(acceptedDetectSegmentId, pendingMark.detectedAtSegmentId());
            acceptedAny = true;
        }
        if (acceptedAny) {
            lastAcceptedPendingDetectSegmentId = acceptedDetectSegmentId;
        }
        sortPendingMarks();
    }

    private void applyImmediateHighPriorityCleanup(List<EpisodePendingMark> detectedMarks) {
        if (detectedMarks == null || detectedMarks.isEmpty() || pendingMarks.isEmpty()) {
            return;
        }
        for (EpisodePendingMark highMark : detectedMarks) {
            if (highMark == null || !highMark.isHighPriority()) {
                continue;
            }
            pendingMarks.removeIf(existing ->
                    existing != null
                            && !existing.sameKey(highMark)
                            && !existing.isHighPriority()
                            && highMark.coversSplitAnchor(existing.splitAnchorSegmentId()));
        }
        sortPendingMarks();
    }

    private void confirmExpiredMarks(FixedActionSegment latestSegment) {
        if (pendingMarks.isEmpty()) {
            return;
        }

        ArrayList<EpisodePendingMark> dueMarks = new ArrayList<>();
        for (EpisodePendingMark pendingMark : pendingMarks) {
            if (pendingMark != null && pendingMark.confirmAtSegmentId() <= latestSegment.getSegmentId()) {
                dueMarks.add(pendingMark);
            }
        }
        dueMarks.sort(Comparator
                .comparingLong(EpisodePendingMark::confirmAtSegmentId)
                .thenComparing((EpisodePendingMark pendingMark) -> pendingMark.isHighPriority() ? 0 : 1)
                .thenComparingLong(EpisodePendingMark::splitAnchorSegmentId)
                .thenComparing(mark -> mark.reason() != null ? mark.reason().name() : ""));

        for (EpisodePendingMark pendingMark : dueMarks) {
            if (!pendingMarks.contains(pendingMark)) {
                continue;
            }
            EpisodeTimeline currentTimeline = snapshotTimeline();
            Optional<EpisodeSplitPoint> confirmed = confirmMark(currentTimeline, pendingMark, latestSegment);
            pendingMarks.remove(pendingMark);
            if (confirmed.isEmpty()) {
                continue;
            }

            EpisodeSplitPoint splitPoint = confirmed.get();
            if (hasSplitPointAtAnchor(splitPoint)) {
                continue;
            }
            splitPoints.add(splitPoint);
            sortSplitPoints();
            clearPendingMarksAround(pendingMark);
            rebuildRegions();
        }
    }

    private Optional<EpisodeSplitPoint> confirmMark(
            EpisodeTimeline currentTimeline,
            EpisodePendingMark pendingMark,
            FixedActionSegment latestSegment) {
        for (EpisodeSplitter splitter : splitters) {
            if (splitter != null
                    && splitter.id().equals(pendingMark.ownerSplitterId())
                    && splitter.supports(pendingMark)) {
                return splitter.confirm(currentTimeline, pendingMark, latestSegment);
            }
        }
        return Optional.empty();
    }

    private void clearPendingMarksAround(EpisodePendingMark confirmedMark) {
        if (confirmedMark == null || pendingMarks.isEmpty()) {
            return;
        }

        pendingMarks.removeIf(pendingMark ->
                pendingMark != null
                        && confirmedMark.coversSplitAnchor(pendingMark.splitAnchorSegmentId()));
        sortPendingMarks();
    }

    private boolean hasSplitPointAtAnchor(EpisodePendingMark pendingMark) {
        if (pendingMark == null) {
            return false;
        }
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint == null || splitPoint.reason() != pendingMark.reason()) {
                continue;
            }
            if (splitPoint.splitAnchorSegmentId() == pendingMark.splitAnchorSegmentId()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSplitPointAtAnchor(EpisodeSplitPoint candidate) {
        if (candidate == null) {
            return false;
        }
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint != null && splitPoint.sameAnchor(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void rebuildRegions() {
        LinkedHashMap<Long, EpisodeRegion> stateSeeds = new LinkedHashMap<>(seededRegionStates);
        for (EpisodeRegion region : regions) {
            if (region != null) {
                stateSeeds.put(region.regionId(), region);
            }
        }
        regions.clear();
        if (segments.isEmpty()) {
            return;
        }

        ArrayList<Long> boundaries = new ArrayList<>();
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint == null) {
                continue;
            }
            long splitRightSegmentId = splitPoint.splitRightSegmentId();
            if (splitPoint.splitLeftSegmentId() > 0L && splitRightSegmentId > 0L) {
                boundaries.add(splitRightSegmentId);
            }
        }
        boundaries.sort(Long::compareTo);

        int regionStart = 0;
        Long lastBoundary = null;
        for (Long boundary : boundaries) {
            if (boundary == null || boundary.equals(lastBoundary)) {
                continue;
            }
            int rightIndex = snapshotTimeline().indexOfSegmentId(boundary);
            if (rightIndex <= 0) {
                lastBoundary = boundary;
                continue;
            }
            int regionEnd = rightIndex - 1;
            EpisodeRegion region = EpisodeIntentMath.buildRegion(
                    segments,
                    regionStart,
                    regionEnd,
                    EpisodeRegionGateDecision.UNSET,
                    EpisodeRegionLifecycleState.HOLDING);
            if (region != null) {
                regions.add(resolveRegionRuntimeState(region, false, stateSeeds));
            }
            regionStart = rightIndex;
            lastBoundary = boundary;
        }

        EpisodeRegion tailRegion = EpisodeIntentMath.buildRegion(
                segments,
                regionStart,
                segments.size() - 1,
                EpisodeRegionGateDecision.UNSET,
                EpisodeRegionLifecycleState.ACTIVE);
        if (tailRegion != null) {
            regions.add(resolveRegionRuntimeState(tailRegion, true, stateSeeds));
        }
    }

    private void seedRegionStates(List<EpisodeRegion> regionStateSeeds) {
        seededRegionStates.clear();
        if (regionStateSeeds == null || regionStateSeeds.isEmpty()) {
            return;
        }
        for (EpisodeRegion region : regionStateSeeds) {
            if (region != null) {
                seededRegionStates.put(region.regionId(), region);
            }
        }
    }

    private EpisodeRegion resolveRegionRuntimeState(
            EpisodeRegion region,
            boolean tailRegion,
            Map<Long, EpisodeRegion> stateSeeds) {
        if (region == null) {
            return null;
        }
        if (tailRegion) {
            return region.withRuntimeState(
                    EpisodeRegionGateDecision.UNSET,
                    EpisodeRegionLifecycleState.ACTIVE);
        }

        EpisodeRegion seed = stateSeeds == null ? null : stateSeeds.get(region.regionId());
        if (seed == null) {
            return region.withRuntimeState(
                    EpisodeRegionGateDecision.UNSET,
                    EpisodeRegionLifecycleState.HOLDING);
        }

        EpisodeRegionGateDecision gateDecision = seed.gateDecision() == null
                ? EpisodeRegionGateDecision.UNSET
                : seed.gateDecision();
        EpisodeRegionLifecycleState lifecycleState = seed.lifecycleState() == null
                ? EpisodeRegionLifecycleState.HOLDING
                : seed.lifecycleState();

        if (lifecycleState == EpisodeRegionLifecycleState.ACTIVE) {
            gateDecision = EpisodeRegionGateDecision.UNSET;
            lifecycleState = EpisodeRegionLifecycleState.HOLDING;
        }

        return region.withRuntimeState(gateDecision, lifecycleState);
    }

    private void sortPendingMarks() {
        pendingMarks.sort(Comparator
                .comparingLong(EpisodePendingMark::confirmAtSegmentId)
                .thenComparingLong(EpisodePendingMark::splitAnchorSegmentId)
                .thenComparing(mark -> mark.reason() != null ? mark.reason().name() : "")
                .thenComparing(EpisodePendingMark::ownerSplitterId));
    }

    private void sortSplitPoints() {
        splitPoints.sort(Comparator
                .comparingLong(EpisodeSplitPoint::splitAnchorSegmentId)
                .thenComparingLong(EpisodeSplitPoint::splitTimestamp)
                .thenComparing(splitPoint -> splitPoint.reason() != null ? splitPoint.reason().name() : ""));
    }

    private void seedBootstrapSplit(FixedActionSegment firstSegment) {
        if (firstSegment == null || !splitPoints.isEmpty()) {
            return;
        }
        long splitTimestamp = Math.max(0L, firstSegment.getStartTimestamp());
        splitPoints.add(new EpisodeSplitPoint(
                EpisodeTimeline.UNASSIGNED_SEGMENT_ID,
                firstSegment.getSegmentId(),
                splitTimestamp,
                firstSegment.getSegmentId(),
                splitTimestamp,
                null,
                null,
                null,
                null,
                null));
        sortSplitPoints();
    }

    private boolean isValidPendingMarkBoundary(EpisodeTimeline timeline, EpisodePendingMark pendingMark) {
        if (timeline == null || pendingMark == null) {
            return false;
        }
        long splitAnchorSegmentId = pendingMark.splitAnchorSegmentId();
        if (splitAnchorSegmentId <= 0L) {
            return false;
        }
        if (pendingMark.splitLeftSegmentId() > 0L
                && timeline.indexOfSegmentId(pendingMark.splitLeftSegmentId()) < 0) {
            return false;
        }
        return pendingMark.splitRightSegmentId() <= 0L
                || timeline.indexOfSegmentId(pendingMark.splitRightSegmentId()) >= 0;
    }
}
