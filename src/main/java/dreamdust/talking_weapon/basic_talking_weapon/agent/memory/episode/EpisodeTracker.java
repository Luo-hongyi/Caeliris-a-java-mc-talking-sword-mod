package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.DeathSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeIntentMath;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.FallbackQuietSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.IntentShiftSplitter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private int lastAcceptedPendingDetectSegmentIndex = -1;

    public EpisodeTracker() {
        this(List.of(
                new IntentShiftSplitter(),
                new DeathSplitter(),
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
        lastAcceptedPendingDetectSegmentIndex = -1;
    }

    public EpisodeTimeline replaceSegments(List<FixedActionSegment> nextSegments) {
        reset();
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

        int nextSegmentIndex = segments.size();
        EpisodeTimeline detectTimeline = snapshotTimeline();
        List<EpisodePendingMark> detectedMarks = detectPendingMarks(detectTimeline, nextSegment, nextSegmentIndex);

        segments.add(nextSegment);
        addPendingMarks(detectedMarks);
        applyImmediateHighPriorityCleanup(detectedMarks);
        rebuildRegions();
        confirmExpiredMarks(nextSegment, nextSegmentIndex);
        rebuildRegions();
        return snapshotTimeline();
    }

    public EpisodeTimeline snapshotTimeline() {
        return new EpisodeTimeline(segments, regions, pendingMarks, splitPoints);
    }

    private List<EpisodePendingMark> detectPendingMarks(
            EpisodeTimeline timeline,
            FixedActionSegment latestSegment,
            int latestSegmentIndex) {
        ArrayList<EpisodePendingMark> detected = new ArrayList<>();
        for (EpisodeSplitter splitter : splitters) {
            if (splitter == null) {
                continue;
            }
            EpisodeDetectContext detectContext = new EpisodeDetectContext(
                    timeline,
                    latestSegment,
                    latestSegmentIndex,
                    detected,
                    lastAcceptedPendingDetectSegmentIndex);
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
        int maxSplitIndex = segments.size();
        int acceptedDetectIndex = lastAcceptedPendingDetectSegmentIndex;
        boolean acceptedAny = false;
        for (EpisodePendingMark pendingMark : detectedMarks) {
            if (pendingMark == null) {
                continue;
            }
            if (pendingMark.splitSegmentIndex() < 0 || pendingMark.splitSegmentIndex() > maxSplitIndex) {
                continue;
            }
            if (currentTimeline.hasPendingMark(pendingMark)) {
                continue;
            }
            if (hasSplitPointAtAnchor(pendingMark)) {
                continue;
            }
            pendingMarks.add(pendingMark);
            acceptedDetectIndex = Math.max(acceptedDetectIndex, pendingMark.detectedAtSegmentIndex());
            acceptedAny = true;
        }
        if (acceptedAny) {
            lastAcceptedPendingDetectSegmentIndex = acceptedDetectIndex;
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
                            && highMark.coversSplitIndex(existing.splitSegmentIndex()));
        }
        sortPendingMarks();
    }

    private void confirmExpiredMarks(FixedActionSegment latestSegment, int latestSegmentIndex) {
        if (pendingMarks.isEmpty()) {
            return;
        }

        ArrayList<EpisodePendingMark> dueMarks = new ArrayList<>();
        for (EpisodePendingMark pendingMark : pendingMarks) {
            if (pendingMark != null && pendingMark.confirmAtSegmentIndex() <= latestSegmentIndex) {
                dueMarks.add(pendingMark);
            }
        }
        dueMarks.sort(Comparator
                .comparingInt(EpisodePendingMark::confirmAtSegmentIndex)
                .thenComparing((EpisodePendingMark pendingMark) -> pendingMark.isHighPriority() ? 0 : 1)
                .thenComparingInt(EpisodePendingMark::splitSegmentIndex)
                .thenComparing(mark -> mark.reason() != null ? mark.reason().name() : ""));

        for (EpisodePendingMark pendingMark : dueMarks) {
            if (!pendingMarks.contains(pendingMark)) {
                continue;
            }
            EpisodeTimeline currentTimeline = snapshotTimeline();
            Optional<EpisodeSplitPoint> confirmed = confirmMark(currentTimeline, pendingMark, latestSegment, latestSegmentIndex);
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
            FixedActionSegment latestSegment,
            int latestSegmentIndex) {
        for (EpisodeSplitter splitter : splitters) {
            if (splitter != null
                    && splitter.id().equals(pendingMark.ownerSplitterId())
                    && splitter.supports(pendingMark)) {
                return splitter.confirm(currentTimeline, pendingMark, latestSegment, latestSegmentIndex);
            }
        }
        return Optional.empty();
    }

    private void clearPendingMarksAround(EpisodePendingMark confirmedMark) {
        if (confirmedMark == null || pendingMarks.isEmpty()) {
            return;
        }

        int minSplitIndex = confirmedMark.splitSegmentIndex() - confirmedMark.coverBeforeSegments();
        int maxSplitIndex = confirmedMark.splitSegmentIndex() + confirmedMark.coverAfterSegments();
        pendingMarks.removeIf(pendingMark ->
                pendingMark != null
                        && pendingMark.splitSegmentIndex() >= minSplitIndex
                        && pendingMark.splitSegmentIndex() <= maxSplitIndex);
        sortPendingMarks();
    }

    private boolean hasSplitPointAtAnchor(EpisodePendingMark pendingMark) {
        if (pendingMark == null) {
            return false;
        }
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint != null
                    && splitPoint.splitSegmentIndex() == pendingMark.splitSegmentIndex()
                    && splitPoint.reason() == pendingMark.reason()) {
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
        regions.clear();
        if (segments.isEmpty()) {
            return;
        }

        ArrayList<Integer> boundaries = new ArrayList<>();
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint == null) {
                continue;
            }
            int splitSegmentIndex = splitPoint.splitSegmentIndex();
            if (splitSegmentIndex > 0 && splitSegmentIndex <= segments.size()) {
                boundaries.add(splitSegmentIndex);
            }
        }
        boundaries.sort(Integer::compareTo);

        int regionStart = 0;
        Integer lastBoundary = null;
        for (Integer boundary : boundaries) {
            if (boundary == null || boundary.equals(lastBoundary)) {
                continue;
            }
            int regionEnd = Math.min(boundary, segments.size()) - 1;
            EpisodeRegion region = EpisodeIntentMath.buildRegion(segments, regionStart, regionEnd);
            if (region != null) {
                regions.add(region);
            }
            regionStart = Math.min(boundary, segments.size());
            lastBoundary = boundary;
        }

        EpisodeRegion tailRegion = EpisodeIntentMath.buildRegion(segments, regionStart, segments.size() - 1);
        if (tailRegion != null) {
            regions.add(tailRegion);
        }
    }

    private void sortPendingMarks() {
        pendingMarks.sort(Comparator
                .comparingInt(EpisodePendingMark::confirmAtSegmentIndex)
                .thenComparingInt(EpisodePendingMark::splitSegmentIndex)
                .thenComparing(mark -> mark.reason() != null ? mark.reason().name() : "")
                .thenComparing(EpisodePendingMark::ownerSplitterId));
    }

    private void sortSplitPoints() {
        splitPoints.sort(Comparator
                .comparingInt(EpisodeSplitPoint::splitSegmentIndex)
                .thenComparingLong(EpisodeSplitPoint::splitTimestamp)
                .thenComparing(splitPoint -> splitPoint.reason() != null ? splitPoint.reason().name() : ""));
    }
}
