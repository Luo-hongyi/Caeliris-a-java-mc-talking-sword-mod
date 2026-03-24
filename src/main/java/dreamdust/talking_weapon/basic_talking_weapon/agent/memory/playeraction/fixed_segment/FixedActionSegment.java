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

    private static final Comparator<FixedActionSegmentEntry> ENTRY_ORDER =
            Comparator.comparingLong(FixedActionSegmentEntry::getFirstTimestamp)
                    .thenComparingLong(FixedActionSegmentEntry::getLastTimestamp)
                    .thenComparingInt(FixedActionSegmentEntry::getRawStartIndex)
                    .thenComparingInt(FixedActionSegmentEntry::getRawEndIndex)
                    .thenComparing(entry -> entry.getType() == null ? "" : entry.getType().name())
                    .thenComparing(entry -> entry.getTarget() == null ? "" : entry.getTarget());

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
        this(startTimestamp, endTimestamp, rawStartIndex, rawEndIndex, entries, null, null);
    }

    public FixedActionSegment(
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<FixedActionSegmentEntry> entries,
            FixedSegmentSplitterSignals splitterSignals) {
        this(startTimestamp, endTimestamp, rawStartIndex, rawEndIndex, entries, splitterSignals, null);
    }

    public FixedActionSegment(
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<FixedActionSegmentEntry> entries,
            FixedSegmentSplitterSignals splitterSignals,
            FixedActionSegmentContext context) {
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

    public FixedActionSegment withContext(FixedActionSegmentContext nextContext) {
        return new FixedActionSegment(
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
