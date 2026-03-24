package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeIntentMath;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Episode v2 的统一正式状态。
 * <p>
 * 第一版只保留：
 * <ul>
 * <li>segments</li>
 * <li>regions</li>
 * <li>pending marks</li>
 * <li>split points</li>
 * </ul>
 */
public record EpisodeTimeline(
        List<FixedActionSegment> segments,
        List<EpisodeRegion> regions,
        List<EpisodePendingMark> pendingMarks,
        List<EpisodeSplitPoint> splitPoints) {

    public EpisodeTimeline {
        segments = segments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(segments));
        regions = regions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(regions));
        pendingMarks = pendingMarks == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(pendingMarks));
        splitPoints = splitPoints == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(splitPoints));
    }

    public static EpisodeTimeline empty() {
        return new EpisodeTimeline(List.of(), List.of(), List.of(), List.of());
    }

    public int segmentCount() {
        return segments.size();
    }

    public FixedActionSegment getSegment(int segmentIndex) {
        return segmentIndex >= 0 && segmentIndex < segments.size() ? segments.get(segmentIndex) : null;
    }

    public List<FixedActionSegment> sliceSegments(int startInclusive, int endInclusive) {
        if (segments.isEmpty()) {
            return List.of();
        }
        int safeStart = Math.max(0, startInclusive);
        int safeEnd = Math.min(endInclusive, segments.size() - 1);
        if (safeStart > safeEnd) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(segments.subList(safeStart, safeEnd + 1)));
    }

    public EpisodeRegion latestRegion() {
        return regions.isEmpty() ? null : regions.get(regions.size() - 1);
    }

    public EpisodeSplitPoint latestSplitPoint() {
        return splitPoints.isEmpty() ? null : splitPoints.get(splitPoints.size() - 1);
    }

    public EpisodeRegion regionBeforeSplit(int splitSegmentIndex) {
        if (splitSegmentIndex <= 0 || segments.isEmpty()) {
            return null;
        }

        int regionStart = 0;
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint == null) {
                continue;
            }
            int boundary = splitPoint.splitSegmentIndex();
            if (boundary >= splitSegmentIndex) {
                break;
            }
            regionStart = Math.max(regionStart, Math.min(boundary, segments.size()));
        }
        return EpisodeIntentMath.buildRegion(segments, regionStart, splitSegmentIndex - 1);
    }

    public boolean hasPendingMark(EpisodePendingMark candidate) {
        if (candidate == null) {
            return false;
        }
        for (EpisodePendingMark pendingMark : pendingMarks) {
            if (pendingMark != null && pendingMark.sameKey(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSplitPoint(EpisodeSplitPoint candidate) {
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
}
