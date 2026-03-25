package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 极轻量的玩家动作记录。
 */
public final class ActionRecord {

    private final ActionType type;
    private final String target; // entity/block registry ID，可 null
    private final int count;
    private final long timestamp;
    private final Set<DetailTag> detailTags;

    public ActionRecord(ActionType type, String target, Set<DetailTag> detailTags) {
        this(type, target, 1, System.currentTimeMillis(), detailTags);
    }

    private ActionRecord(ActionType type, String target, int count, long timestamp, Set<DetailTag> detailTags) {
        this.type = type;
        this.target = target;
        this.count = count;
        this.timestamp = timestamp;
        this.detailTags = (detailTags != null)
                ? Collections.unmodifiableSet(new LinkedHashSet<>(detailTags))
                : Collections.emptySet();
    }

    /** 便捷工厂：无细节标签 */
    public static ActionRecord of(ActionType type, String target) {
        return new ActionRecord(type, target, null);
    }

    /** 便捷工厂：带细节标签 */
    public static ActionRecord of(ActionType type, String target, Set<DetailTag> tags) {
        return new ActionRecord(type, target, tags);
    }

    // ─────────────────────────────────────────────
    // Getters
    // ─────────────────────────────────────────────

    public ActionType getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    public int getCount() {
        return count;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Set<DetailTag> getDetailTags() {
        return detailTags;
    }

    /**
     * 返回一份独立副本，避免同一条记录在不同存储层之间共享可变状态。
     */
    public ActionRecord copy() {
        return new ActionRecord(type, target, count, timestamp, detailTags);
    }

    // ─────────────────────────────────────────────
    // 显示
    // ─────────────────────────────────────────────

    /**
     * 返回人类可读的摘要，如 "ATTACK zombie ×5"。
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name());
        if (target != null)
            sb.append(' ').append(target);
        if (count > 1)
            sb.append(" ×").append(count);
        return sb.toString();
    }

    @Override
    public String toString() {
        return toSummary();
    }
}
