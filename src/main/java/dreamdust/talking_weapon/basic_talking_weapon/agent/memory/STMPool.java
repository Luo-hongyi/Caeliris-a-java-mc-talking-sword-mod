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
    
    /** 领域疲劳账单：记录在这个纪元里，哪种领域引发了多少次分数变动 */
    private final java.util.Map<dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag, Integer> domainFatigue;
    
    /** 分标签暂存好感账本：记录本期尚未合成入最后总账的好感变动 */
    private final java.util.Map<dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag, Integer> volatileFavorabilityByTag;
    
    /** 单个领域允许的单期好感度变动极限 */
    private static final int MAX_FAVORABILITY_CHANGE_PER_TAG_PER_EPOCH = 30;
    private final int contextCapacity;
    private final int turnCapacity;

    public STMPool(int contextCapacity, int turnCapacity) {
        this.contextEntries = new ArrayList<>();
        this.interactionTurns = new ArrayList<>();
        this.domainFatigue = new java.util.concurrent.ConcurrentHashMap<>();
        this.volatileFavorabilityByTag = new java.util.concurrent.ConcurrentHashMap<>();
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
        domainFatigue.clear();
        volatileFavorabilityByTag.clear();
    }

    /**
     * 获取本纪元的暂态（未定案）好感度账单副本。
     */
    public synchronized java.util.Map<dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag, Integer> getVolatileFavorabilityByTag() {
        return new java.util.HashMap<>(volatileFavorabilityByTag);
    }

    /**
     * 向 STM 请求变动好感度，STM 根据可见上文的领域权重进行划分并对各标签实施递减。
     * @param requestedChange LLM 申请的好感度总体变动量
     * @param visibleContexts 当前由于注意力机制真正传递给了大模型的上下文条目列表
     * @return 实际被允许应用到该纪元暂存账本的有效总变动量
     */
    public synchronized int requestFavorabilityChange(int requestedChange, List<TaggedContextEntry> visibleContexts) {
        if (requestedChange == 0) return 0;

        java.util.Map<dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag, Double> tagWeights = new java.util.HashMap<>();
        int totalTags = 0;
        
        if (visibleContexts != null) {
            for (TaggedContextEntry entry : visibleContexts) {
                for (dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag tag : entry.getFocusTags()) {
                    tagWeights.put(tag, tagWeights.getOrDefault(tag, 0.0) + 1.0);
                    totalTags++;
                }
            }
        }

        if (totalTags == 0) {
            // 没有有效领域标签时，默认分配为 COMMON
            tagWeights.put(dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag.COMMON, 1.0);
            totalTags = 1;
        }

        int totalAppliedChange = 0;

        for (java.util.Map.Entry<dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag, Double> tagEntry : tagWeights.entrySet()) {
            dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag tag = tagEntry.getKey();
            double weight = tagEntry.getValue() / totalTags;

            // 分摊到该标签的请求份额
            int share = (int) Math.round(requestedChange * weight);
            if (share == 0) continue;

            int fatigue = domainFatigue.getOrDefault(tag, 0);

            // 边际衰减：申请量 / (2 ^ 疲劳度)，封顶向右位移 10 次也就是除以 1024 基本归 0
            int scaleShift = Math.min(fatigue, 10);
            int decayedShare = share / (1 << scaleShift);

            if (decayedShare == 0) {
                // 已经因刷得太多被衰减为 0，依然算作一次疲劳加深
                domainFatigue.put(tag, fatigue + 1);
                continue;
            }

            int oldAcc = volatileFavorabilityByTag.getOrDefault(tag, 0);
            int newAcc = oldAcc + decayedShare;

            // 应用极限截断，防止单领域大量刷好感
            if (newAcc > MAX_FAVORABILITY_CHANGE_PER_TAG_PER_EPOCH) {
                newAcc = MAX_FAVORABILITY_CHANGE_PER_TAG_PER_EPOCH;
            } else if (newAcc < -MAX_FAVORABILITY_CHANGE_PER_TAG_PER_EPOCH) {
                newAcc = -MAX_FAVORABILITY_CHANGE_PER_TAG_PER_EPOCH;
            }

            int appliedForTag = newAcc - oldAcc;
            volatileFavorabilityByTag.put(tag, newAcc);

            if (appliedForTag != 0) {
                domainFatigue.put(tag, fatigue + 1);
                totalAppliedChange += appliedForTag;
            }
        }

        return totalAppliedChange;
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
