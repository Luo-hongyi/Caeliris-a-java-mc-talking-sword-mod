package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * moving action buffer 的单段快照。
 * <p>
 * 该段只服务 prompt / 近期感知层，不参与 episode 与最终 LTM 生成边界。
 */
public final class MovingActionSegment implements ActionSegmentView {

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
        return -1;
    }

    @Override
    public int getRawEndIndex() {
        return -1;
    }

    public boolean isOpen() {
        return open;
    }

    @Override
    public List<MovingActionSegmentEntry> getEntries() {
        return entries;
    }
}
