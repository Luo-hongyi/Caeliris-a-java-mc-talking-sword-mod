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

    public static final long UNASSIGNED_SEGMENT_ID = FixedActionSegment.UNASSIGNED_SEGMENT_ID;

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

    public long getSegmentId(int segmentIndex) {
        FixedActionSegment segment = getSegment(segmentIndex);
        return segment == null ? UNASSIGNED_SEGMENT_ID : segment.getSegmentId();
    }

    public int indexOfSegmentId(long segmentId) {
        if (segmentId <= 0L || segments.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment != null && segment.getSegmentId() == segmentId) {
                return i;
            }
        }
        return -1;
    }

    public FixedActionSegment getSegmentById(long segmentId) {
        int index = indexOfSegmentId(segmentId);
        return index < 0 ? null : getSegment(index);
    }

    public int segmentCountBetween(long startSegmentId, long endSegmentId) {
        int startIndex = indexOfSegmentId(startSegmentId);
        int endIndex = indexOfSegmentId(endSegmentId);
        if (startIndex < 0 || endIndex < startIndex) {
            return 0;
        }
        return endIndex - startIndex + 1;
    }

    public int segmentCountOf(EpisodeRegion region) {
        if (region == null) {
            return 0;
        }
        return segmentCountBetween(region.startSegmentId(), region.endSegmentId());
    }

    public BoundarySegmentIds boundaryBeforeSegment(long rightSegmentId) {
        int rightIndex = indexOfSegmentId(rightSegmentId);
        if (rightIndex < 0) {
            return new BoundarySegmentIds(UNASSIGNED_SEGMENT_ID, UNASSIGNED_SEGMENT_ID);
        }
        long leftSegmentId = rightIndex <= 0
                ? UNASSIGNED_SEGMENT_ID
                : getSegmentId(rightIndex - 1);
        return new BoundarySegmentIds(leftSegmentId, rightSegmentId);
    }

    public BoundarySegmentIds boundaryAfterSegment(long leftSegmentId) {
        int leftIndex = indexOfSegmentId(leftSegmentId);
        if (leftIndex < 0) {
            return new BoundarySegmentIds(UNASSIGNED_SEGMENT_ID, UNASSIGNED_SEGMENT_ID);
        }
        long rightSegmentId = leftIndex + 1 >= segments.size()
                ? UNASSIGNED_SEGMENT_ID
                : getSegmentId(leftIndex + 1);
        return new BoundarySegmentIds(leftSegmentId, rightSegmentId);
    }

    public BoundarySegmentIds resolveBoundarySegmentIds(long splitLeftSegmentId, long splitRightSegmentId) {
        if (splitLeftSegmentId > 0L && splitRightSegmentId > 0L) {
            return new BoundarySegmentIds(splitLeftSegmentId, splitRightSegmentId);
        }
        if (splitLeftSegmentId > 0L) {
            return boundaryAfterSegment(splitLeftSegmentId);
        }
        if (splitRightSegmentId > 0L) {
            return boundaryBeforeSegment(splitRightSegmentId);
        }
        return new BoundarySegmentIds(UNASSIGNED_SEGMENT_ID, UNASSIGNED_SEGMENT_ID);
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

    public List<FixedActionSegment> sliceSegmentsById(long startSegmentId, long endSegmentId) {
        int startIndex = indexOfSegmentId(startSegmentId);
        int endIndex = indexOfSegmentId(endSegmentId);
        if (startIndex < 0 || endIndex < startIndex) {
            return List.of();
        }
        return sliceSegments(startIndex, endIndex);
    }

    public EpisodeRegion latestRegion() {
        return regions.isEmpty() ? null : regions.get(regions.size() - 1);
    }

    public EpisodeSplitPoint latestSplitPoint() {
        return splitPoints.isEmpty() ? null : splitPoints.get(splitPoints.size() - 1);
    }

    public EpisodeRegion regionBeforeBoundary(long splitLeftSegmentId, long splitRightSegmentId) {
        BoundarySegmentIds boundary = resolveBoundarySegmentIds(splitLeftSegmentId, splitRightSegmentId);
        if (boundary.leftSegmentId() <= 0L || segments.isEmpty()) {
            return null;
        }

        int regionEnd = indexOfSegmentId(boundary.leftSegmentId());
        if (regionEnd < 0) {
            return null;
        }
        int regionStart = 0;
        for (EpisodeSplitPoint splitPoint : splitPoints) {
            if (splitPoint == null) {
                continue;
            }
            long regionRightSegmentId = splitPoint.splitRightSegmentId();
            if (regionRightSegmentId <= 0L) {
                continue;
            }
            int candidateStart = indexOfSegmentId(regionRightSegmentId);
            if (candidateStart < 0) {
                continue;
            }
            if (candidateStart > regionEnd) {
                break;
            }
            regionStart = Math.max(regionStart, candidateStart);
        }
        return EpisodeIntentMath.buildRegion(segments, regionStart, regionEnd);
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

    public record BoundarySegmentIds(
            long leftSegmentId,
            long rightSegmentId) {
    }
}
