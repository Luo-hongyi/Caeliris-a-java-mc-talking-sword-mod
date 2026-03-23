package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * moving action buffer 中单个聚合条目的不可变快照。
 * <p>
 * 当前仍按 {@code same type + same target} 聚合，
 * 但其生命周期由 moving segment 决定，而不是 fixed 60 秒窗口。
 */
public final class MovingActionSegmentEntry {

    private final ActionType type;
    private final String target;
    private final int count;
    private final long firstTimestamp;
    private final long lastTimestamp;
    private final Set<DetailTag> detailTags;
    private final boolean folded;
    private final List<String> foldedTargets;

    public MovingActionSegmentEntry(
            ActionType type,
            String target,
            int count,
            long firstTimestamp,
            long lastTimestamp,
            Set<DetailTag> detailTags) {
        this(type, target, count, firstTimestamp, lastTimestamp, detailTags, false, List.of());
    }

    public MovingActionSegmentEntry(
            ActionType type,
            String target,
            int count,
            long firstTimestamp,
            long lastTimestamp,
            Set<DetailTag> detailTags,
            boolean folded,
            List<String> foldedTargets) {
        this.type = type;
        this.target = target;
        this.count = count;
        this.firstTimestamp = firstTimestamp;
        this.lastTimestamp = lastTimestamp;
        this.detailTags = detailTags == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(detailTags));
        this.folded = folded;
        this.foldedTargets = foldedTargets == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(foldedTargets));
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

    public Set<DetailTag> getDetailTags() {
        return detailTags;
    }

    public boolean isFolded() {
        return folded;
    }

    public List<String> getFoldedTargets() {
        return foldedTargets;
    }
}
