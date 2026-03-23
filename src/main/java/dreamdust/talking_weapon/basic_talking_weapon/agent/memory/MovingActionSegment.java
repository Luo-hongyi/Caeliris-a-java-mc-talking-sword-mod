package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * moving action buffer 的单段快照。
 * <p>
 * 该段只服务 prompt / 近期感知层，不参与 episode 与最终 LTM 生成边界。
 */
public final class MovingActionSegment {

    public static final String FOLDED_BLOCK_TARGET = "various_blocks";

    private final long startTimestamp;
    private final long endTimestamp;
    private final boolean open;
    private final List<MovingActionSegmentEntry> entries;

    public MovingActionSegment(
            long startTimestamp,
            long endTimestamp,
            boolean open,
            List<MovingActionSegmentEntry> entries) {
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.open = open;
        this.entries = entries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public boolean isOpen() {
        return open;
    }

    public List<MovingActionSegmentEntry> getEntries() {
        return entries;
    }

    public ActionCompactedSegment toCompactedSegmentView() {
        List<ActionCompactedEntry> compactedEntries = new ArrayList<>(entries.size());
        for (MovingActionSegmentEntry entry : entries) {
            compactedEntries.add(new ActionCompactedEntry(
                    entry.getType(),
                    entry.getTarget(),
                    entry.getCount(),
                    entry.getFirstTimestamp(),
                    entry.getLastTimestamp(),
                    -1,
                    -1,
                    entry.getDetailTags()));
        }
        return new ActionCompactedSegment(startTimestamp, endTimestamp, -1, -1, compactedEntries);
    }
}
