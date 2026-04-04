package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.CollectedContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 短期记忆聚合池 (Short-Term Memory Pool)。
 * <p>
 * 统一的时间线缓冲区，收集当前纪元内的<b>所有</b>上下文和 Agent 输出，
 * 供后续长期记忆 (LTM) 合成任务读取。
 * <p>
 * <b>分区设计：</b>
 * <ul>
 *   <li>{@link #contextEntries} — 玩家/环境视角的全量上下文（未经注意力过滤）</li>
 *   <li>{@link #interactionTurns} — 玩家与剑灵的交互回合（带有输入 Prompt 和输出 Actions）</li>
 * </ul>
 * <p>
 * <b>注意力过滤仅在提取时使用</b>（组装即时 Prompt 或 LTM 合成 TopK），
 * 写入 STM 时不做任何过滤。
 * <p>
 * 线程安全：所有公共方法均 synchronized。
 */
public final class STMPool {

    private final List<TaggedContextEntry> contextEntries;
    private final List<InteractionTurn> interactionTurns;
    private final int contextCapacity;
    private final int turnCapacity;

    public STMPool(int contextCapacity, int turnCapacity) {
        this.contextEntries = new ArrayList<>();
        this.interactionTurns = new ArrayList<>();
        this.contextCapacity = contextCapacity;
        this.turnCapacity = turnCapacity;
    }

    public STMPool() {
        this(512, 128);
    }

    // ═══════════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 批量推入全量上下文条目（未经注意力过滤）。
     * 每次 Agent 被触发时，所有采集到的 rawEntries 都应推入此处。
     */
    public synchronized void pushContextAll(List<TaggedContextEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        contextEntries.addAll(CollectedContextSnapshot.memoryEligibleEntries(entries));
        // 超容量时淘汰最旧的
        while (contextEntries.size() > contextCapacity) {
            contextEntries.remove(0);
        }
    }

    /**
     * 推入完整的交互回合。
     */
    public synchronized void pushInteractionTurn(InteractionTurn turn) {
        if (turn == null) return;
        interactionTurns.add(turn);
        while (interactionTurns.size() > turnCapacity) {
            interactionTurns.remove(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 读取
    // ═══════════════════════════════════════════════════════════════

    /**
     * 返回所有上下文条目的不可变快照（按写入顺序，即时间顺序）。
     */
    public synchronized List<TaggedContextEntry> contextSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(contextEntries));
    }

    /**
     * 返回所有回合记录的不可变快照。
     */
    public synchronized List<InteractionTurn> turnSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(interactionTurns));
    }

    /**
     * 返回某个时间点之前的 STM 切片视图，不修改池本身。
     * <p>
     * 当前用于为 episode 驱动的 LTM 生成预留按时间边界切片的能力。
     */
    public synchronized TimeSlice snapshotBefore(long exclusiveTimestamp) {
        List<TaggedContextEntry> contextSlice = new ArrayList<>();
        for (TaggedContextEntry entry : contextEntries) {
            if (entry != null && entry.getTimestamp() < exclusiveTimestamp) {
                contextSlice.add(entry);
            }
        }

        List<InteractionTurn> turnSlice = new ArrayList<>();
        for (InteractionTurn turn : interactionTurns) {
            if (turn != null && turn.getTimestamp() < exclusiveTimestamp) {
                turnSlice.add(turn);
            }
        }

        return new TimeSlice(contextSlice, turnSlice);
    }

    /**
     * 返回某个时间区间内的 STM 切片视图，不修改池本身。
     * <p>
     * 当前为 episode gate 预留，供其只查看“某个 region 内部”的 STM 记录，
     * 而不是把整个 epoch 早期 STM 都一并喂进去。
     */
    public synchronized TimeSlice snapshotBetween(long startInclusiveTimestamp, long endExclusiveTimestamp) {
        long safeStart = Math.max(0L, startInclusiveTimestamp);
        if (endExclusiveTimestamp <= safeStart) {
            return new TimeSlice(List.of(), List.of());
        }

        List<TaggedContextEntry> contextSlice = new ArrayList<>();
        for (TaggedContextEntry entry : contextEntries) {
            if (entry != null
                    && entry.getTimestamp() >= safeStart
                    && entry.getTimestamp() < endExclusiveTimestamp) {
                contextSlice.add(entry);
            }
        }

        List<InteractionTurn> turnSlice = new ArrayList<>();
        for (InteractionTurn turn : interactionTurns) {
            if (turn != null
                    && turn.getTimestamp() >= safeStart
                    && turn.getTimestamp() < endExclusiveTimestamp) {
                turnSlice.add(turn);
            }
        }

        return new TimeSlice(contextSlice, turnSlice);
    }

    /**
     * 按 owner-side source material window 提取对应的 STM 切片。
     * <p>
     * STMPool 只消费 window 中的 STM 时间边界，不关心 segment range 本身。
     */
    public synchronized TimeSlice snapshotForWindow(MemorySourceMaterialWindow window) {
        if (window == null) {
            return new TimeSlice(List.of(), List.of());
        }
        return snapshotBetween(
                window.stmSliceStartTimestamp(),
                window.stmSliceExclusiveTimestamp());
    }

    /**
     * 丢弃某个时间点之前的 STM 数据。
     * <p>
     * 第一版 episode split 暂不直接调用，后续 LTM 生成切片阶段再接入。
     */
    public synchronized void discardBefore(long exclusiveTimestamp) {
        contextEntries.removeIf(entry -> entry != null && entry.getTimestamp() < exclusiveTimestamp);
        interactionTurns.removeIf(turn -> turn != null && turn.getTimestamp() < exclusiveTimestamp);
    }

    /**
     * 丢弃某个时间区间内的 STM 数据。
     * <p>
     * 这是后续 episode reject cleanup / synthesis consumed cleanup 的正式入口之一，
     * 语义固定为删除 `[startInclusiveTimestamp, endExclusiveTimestamp)` 内的记录。
     */
    public synchronized void discardBetween(long startInclusiveTimestamp, long endExclusiveTimestamp) {
        long safeStart = Math.max(0L, startInclusiveTimestamp);
        if (endExclusiveTimestamp <= safeStart) {
            return;
        }
        contextEntries.removeIf(entry ->
                entry != null
                        && entry.getTimestamp() >= safeStart
                        && entry.getTimestamp() < endExclusiveTimestamp);
        interactionTurns.removeIf(turn ->
                turn != null
                        && turn.getTimestamp() >= safeStart
                        && turn.getTimestamp() < endExclusiveTimestamp);
    }

    /**
     * 按 owner-side source material window 丢弃对应的 STM 记录。
     */
    public synchronized void discardForWindow(MemorySourceMaterialWindow window) {
        if (window == null) {
            return;
        }
        discardBetween(
                window.stmSliceStartTimestamp(),
                window.stmSliceExclusiveTimestamp());
    }

    /**
     * 上下文条目总数。
     */
    public synchronized int contextSize() {
        return contextEntries.size();
    }

    /**
     * 回合记录总数。
     */
    public synchronized int turnSize() {
        return interactionTurns.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 清空所有数据（LTM 合成完成后调用）。
     */
    public synchronized void clear() {
        contextEntries.clear();
        interactionTurns.clear();
    }

    @Override
    public String toString() {
        return "STMPool{context=" + contextEntries.size()
                + ", turns=" + interactionTurns.size() + "}";
    }

    public static final class TimeSlice {
        private final List<TaggedContextEntry> contextEntries;
        private final List<InteractionTurn> interactionTurns;

        private TimeSlice(List<TaggedContextEntry> contextEntries, List<InteractionTurn> interactionTurns) {
            this.contextEntries = contextEntries == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(contextEntries));
            this.interactionTurns = interactionTurns == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(interactionTurns));
        }

        public static TimeSlice empty() {
            return new TimeSlice(List.of(), List.of());
        }

        public List<TaggedContextEntry> getContextEntries() {
            return contextEntries;
        }

        public List<InteractionTurn> getInteractionTurns() {
            return interactionTurns;
        }

        public boolean isEmpty() {
            return contextEntries.isEmpty() && interactionTurns.isEmpty();
        }
    }
}
