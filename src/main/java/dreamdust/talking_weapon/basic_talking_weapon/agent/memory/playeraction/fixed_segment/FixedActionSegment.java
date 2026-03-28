package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * 玩家原始动作归档层产出的正式 fixed segment。
 * <p>
 * 这是 episode / studio / LTM 使用的统一分钟级正式对象，
 * 不再只是某次读取时临时现算出来的压缩视图。
 */
public final class FixedActionSegment implements ActionSegmentView {

    public static final long UNASSIGNED_SEGMENT_ID = -1L;

    private static final Comparator<FixedActionSegmentEntry> ENTRY_ORDER =
            Comparator.comparingLong(FixedActionSegmentEntry::getFirstTimestamp)
                    .thenComparingLong(FixedActionSegmentEntry::getLastTimestamp)
                    .thenComparingInt(FixedActionSegmentEntry::getRawStartIndex)
                    .thenComparingInt(FixedActionSegmentEntry::getRawEndIndex)
                    .thenComparing(entry -> entry.getType() == null ? "" : entry.getType().name())
                    .thenComparing(entry -> entry.getTarget() == null ? "" : entry.getTarget());

    private final long segmentId;
    private final long startTimestamp;
    private final long endTimestamp;
    private final int rawStartIndex;
    private final int rawEndIndex;
    private final List<FixedActionSegmentEntry> entries;
    private final FixedSegmentSplitterSignals splitterSignals;
    private final FixedActionSegmentContext context;

    public FixedActionSegment(
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<FixedActionSegmentEntry> entries) {
        this(UNASSIGNED_SEGMENT_ID, startTimestamp, endTimestamp, rawStartIndex, rawEndIndex, entries, null, null);
    }

    public FixedActionSegment(
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<FixedActionSegmentEntry> entries,
            FixedSegmentSplitterSignals splitterSignals) {
        this(UNASSIGNED_SEGMENT_ID, startTimestamp, endTimestamp, rawStartIndex, rawEndIndex, entries, splitterSignals, null);
    }

    public FixedActionSegment(
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<FixedActionSegmentEntry> entries,
            FixedSegmentSplitterSignals splitterSignals,
            FixedActionSegmentContext context) {
        this(UNASSIGNED_SEGMENT_ID, startTimestamp, endTimestamp, rawStartIndex, rawEndIndex, entries, splitterSignals, context);
    }

    public FixedActionSegment(
            long segmentId,
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<FixedActionSegmentEntry> entries,
            FixedSegmentSplitterSignals splitterSignals,
            FixedActionSegmentContext context) {
        this.segmentId = segmentId;
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.rawStartIndex = rawStartIndex;
        this.rawEndIndex = rawEndIndex;
        this.entries = immutableSortedEntries(entries);
        this.splitterSignals = splitterSignals == null
                ? FixedSegmentSplitterSignals.fromSegment(this)
                : splitterSignals;
        this.context = context == null ? FixedActionSegmentContext.empty() : context;
    }

    public long getSegmentId() {
        return segmentId;
    }

    @Override
    public long getStartTimestamp() {
        return startTimestamp;
    }

    @Override
    public long getEndTimestamp() {
        return endTimestamp;
    }

    @Override
    public int getRawStartIndex() {
        return rawStartIndex;
    }

    @Override
    public int getRawEndIndex() {
        return rawEndIndex;
    }

    @Override
    public List<FixedActionSegmentEntry> getEntries() {
        return entries;
    }

    public FixedSegmentSplitterSignals getSplitterSignals() {
        return splitterSignals;
    }

    public FixedActionSegmentContext getContext() {
        return context;
    }

    public boolean hasAssignedSegmentId() {
        return segmentId > 0L;
    }

    public boolean sameStableIdentity(FixedActionSegment other) {
        if (other == null) {
            return false;
        }
        if (hasAssignedSegmentId() && other.hasAssignedSegmentId()) {
            return segmentId == other.segmentId
                    && startTimestamp == other.startTimestamp
                    && endTimestamp == other.endTimestamp;
        }
        return startTimestamp == other.startTimestamp
                && endTimestamp == other.endTimestamp
                && rawStartIndex == other.rawStartIndex
                && rawEndIndex == other.rawEndIndex;
    }

    public String stableIdentityToken() {
        if (hasAssignedSegmentId()) {
            return segmentId + ":" + startTimestamp + ":" + endTimestamp;
        }
        return "unassigned:"
                + startTimestamp
                + ":"
                + endTimestamp
                + ":"
                + rawStartIndex
                + ":"
                + rawEndIndex;
    }

    public FixedActionSegment withSegmentId(long nextSegmentId) {
        return new FixedActionSegment(
                nextSegmentId,
                startTimestamp,
                endTimestamp,
                rawStartIndex,
                rawEndIndex,
                entries,
                splitterSignals,
                context);
    }

    public FixedActionSegment withContext(FixedActionSegmentContext nextContext) {
        return new FixedActionSegment(
                segmentId,
                startTimestamp,
                endTimestamp,
                rawStartIndex,
                rawEndIndex,
                entries,
                splitterSignals,
                nextContext);
    }

    private static List<FixedActionSegmentEntry> immutableSortedEntries(List<FixedActionSegmentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<FixedActionSegmentEntry> ordered = new ArrayList<>(entries);
        ordered.sort(ENTRY_ORDER);
        return Collections.unmodifiableList(ordered);
    }
}
