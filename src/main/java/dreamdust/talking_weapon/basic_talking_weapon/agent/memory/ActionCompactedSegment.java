package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ActionArchive 压缩视图中的统一时间窗口。
 * <p>
 * 所有动作先按固定时长窗口切段，再在段内按类型与目标做聚合。
 */
public final class ActionCompactedSegment {

    private final long startTimestamp;
    private final long endTimestamp;
    private final int rawStartIndex;
    private final int rawEndIndex;
    private final List<ActionCompactedEntry> entries;

    public ActionCompactedSegment(
            long startTimestamp,
            long endTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            List<ActionCompactedEntry> entries) {
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
        this.rawStartIndex = rawStartIndex;
        this.rawEndIndex = rawEndIndex;
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

    public int getRawStartIndex() {
        return rawStartIndex;
    }

    public int getRawEndIndex() {
        return rawEndIndex;
    }

    public List<ActionCompactedEntry> getEntries() {
        return entries;
    }
}
