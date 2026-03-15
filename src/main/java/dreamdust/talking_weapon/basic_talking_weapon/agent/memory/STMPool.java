package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

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
        contextEntries.addAll(entries);
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
}
