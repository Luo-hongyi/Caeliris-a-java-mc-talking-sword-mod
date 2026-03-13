package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 极轻量的玩家动作记录。
 * <p>
 * 当多条同类型 + 同目标的动作在短时间内连续发生时，
 * {@link ActionBuffer} 会将它们合并为一条记录并递增 {@link #count}。
 * <p>
 * 此类本身是<b>可变的</b>（仅 count 字段），但只有 ActionBuffer 内部会修改。
 */
public final class ActionRecord {

    private final ActionType type;
    private final String target; // entity/block registry ID，可 null
    private int count; // 合并计数，默认 1
    private final long timestamp; // 首次发生时间
    private final Set<DetailTag> detailTags;

    public ActionRecord(ActionType type, String target, Set<DetailTag> detailTags) {
        this.type = type;
        this.target = target;
        this.count = 1;
        this.timestamp = System.currentTimeMillis();
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

    // ─────────────────────────────────────────────
    // 合并支持（仅 ActionBuffer 内部调用）
    // ─────────────────────────────────────────────

    /** 递增合并计数 */
    void incrementCount() {
        this.count++;
    }

    /**
     * 是否可与另一条记录合并：同类型 + 同目标 + 时间差 < maxGapMs。
     */
    boolean canMergeWith(ActionRecord other, long maxGapMs) {
        if (this.type != other.type)
            return false;
        if (this.target == null && other.target == null)
            return true;
        if (this.target == null || other.target == null)
            return false;
        return this.target.equals(other.target)
                && Math.abs(other.timestamp - this.timestamp) < maxGapMs;
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
