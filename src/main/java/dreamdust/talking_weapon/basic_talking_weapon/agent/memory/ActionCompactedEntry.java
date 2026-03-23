package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ActionArchive 压缩视图中的单个聚合条目。
 * <p>
 * 同一时间段内，仅合并“同 ActionType + 同 target”的动作。
 * 不跨段合并，也不跨类型合并。
 */
public final class ActionCompactedEntry {

    private final ActionType type;
    private final String target;
    private final int count;
    private final long firstTimestamp;
    private final long lastTimestamp;
    private final int rawStartIndex;
    private final int rawEndIndex;
    private final Set<DetailTag> detailTags;

    public ActionCompactedEntry(
            ActionType type,
            String target,
            int count,
            long firstTimestamp,
            long lastTimestamp,
            int rawStartIndex,
            int rawEndIndex,
            Set<DetailTag> detailTags) {
        this.type = type;
        this.target = target;
        this.count = count;
        this.firstTimestamp = firstTimestamp;
        this.lastTimestamp = lastTimestamp;
        this.rawStartIndex = rawStartIndex;
        this.rawEndIndex = rawEndIndex;
        this.detailTags = detailTags == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(detailTags));
    }

    public ActionType getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    public int getCount() {
        return count;
    }

    public long getFirstTimestamp() {
        return firstTimestamp;
    }

    public long getLastTimestamp() {
        return lastTimestamp;
    }

    public int getRawStartIndex() {
        return rawStartIndex;
    }

    public int getRawEndIndex() {
        return rawEndIndex;
    }

    public Set<DetailTag> getDetailTags() {
        return detailTags;
    }
}
