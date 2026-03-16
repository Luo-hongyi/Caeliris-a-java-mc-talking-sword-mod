package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Agent 记忆系统：三池架构。
 * <ol>
 * <li><b>Identity</b> — 人格身份 (system prompt)，永久不变</li>
 * <li><b>ActionBuffer</b> — 环形动作流，高频轻量，不触发 LLM</li>
 * <li><b>LTMRecords</b> — 剑视角的深度记忆切片列表 / 偏好设定</li>
 * </ol>
 * <p>
 * 线程安全说明：ActionBuffer 自身线程安全；ltmRecords
 * 的访问集中在 AgentRuntime 的 worker 线程中，调用方确保不并发写。
 */
public class AgentMemory {

    // ═══════════════════════════════════════════════════════════════
    // Layer 0: Identity（永久）
    // ═══════════════════════════════════════════════════════════════

    private String identity;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1: ActionBuffer（环形动作流）
    // ═══════════════════════════════════════════════════════════════

    private final ActionBuffer actionBuffer;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1.5: STMPool (短期记忆聚合池)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 短期记忆聚合池：存放当前纪元内的全量上下文条目和 Agent 自身产出。
     * 供 LTM 合成任务读取，合成完成后清空。
     */
    private final STMPool stmPool;

    // ═══════════════════════════════════════════════════════════════
    // Layer 3: LTMRecords（长期记忆切片库）
    // ═══════════════════════════════════════════════════════════════

    private final List<LTMEntry> ltmRecords;
    private int ltmRecordsCapacity;
    private final Deque<UUID> recentRecalledMemoryIds;
    private int recentRecallWindow;

    public AgentMemory() {
        this.identity = "";
        this.actionBuffer = new ActionBuffer(64);
        this.stmPool = new STMPool();
        this.ltmRecords = new java.util.ArrayList<>();
        this.ltmRecordsCapacity = 100;
        this.recentRecalledMemoryIds = new ArrayDeque<>();
        this.recentRecallWindow = 6;

        // 加载外部的静态植入记忆
        try {
            List<LTMEntry> implanted = LTMStorage.loadGlobalImplantedMemories();
            this.ltmRecords.addAll(implanted);
        } catch (Exception e) {
            System.err.println("[TalkingWeapon] Failed to load implanted LTM records: " + e.getMessage());
        }
    }

    // ── Identity ─────────────────────────────────────────────────

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    // ── ActionBuffer ─────────────────────────────────────────────

    public ActionBuffer getActionBuffer() {
        return actionBuffer;
    }

    public STMPool getSTMPool() {
        return stmPool;
    }

    // ── LTMRecords ────────────────────────────────────────────

    /**
     * 追加一条长期记忆 / 预设偏好。超出容量时移除最旧的（注意后续需要接入遗忘机制）。
     */
    public void addLTMRecord(LTMEntry record) {
        ltmRecords.add(record);
        while (ltmRecords.size() > ltmRecordsCapacity) {
            ltmRecords.remove(0);
        }
    }

    /**
     * 重载全局的植入记忆。清理现有记录中的 IMPLANTED 并重新读取加载。
     */
    public void reloadImplantedMemories() {
        ltmRecords.removeIf(entry -> entry.getSourceType() == LTMEntry.SourceType.IMPLANTED);
        try {
            List<LTMEntry> implanted = LTMStorage.loadGlobalImplantedMemories();
            ltmRecords.addAll(implanted);
        } catch (Exception e) {
            System.err.println("[TalkingWeapon] Failed to reload implanted LTM records: " + e.getMessage());
        }
    }

    public List<LTMEntry> getLtmRecords() {
        return Collections.unmodifiableList(ltmRecords);
    }

    public int getLtmRecordsCapacity() {
        return ltmRecordsCapacity;
    }

    public void setLtmRecordsCapacity(int capacity) {
        this.ltmRecordsCapacity = capacity;
    }

    public synchronized void setRecentRecallWindow(int window) {
        this.recentRecallWindow = Math.max(1, window);
        trimRecentRecallQueue();
    }

    public synchronized int getRecentRecallWindow() {
        return recentRecallWindow;
    }

    public synchronized void noteRecalledMemories(List<LTMEntry> recalledEntries) {
        if (recalledEntries == null || recalledEntries.isEmpty()) {
            return;
        }
        for (LTMEntry entry : recalledEntries) {
            if (entry == null || entry.getId() == null) {
                continue;
            }
            recentRecalledMemoryIds.remove(entry.getId());
            recentRecalledMemoryIds.addFirst(entry.getId());
        }
        trimRecentRecallQueue();
    }

    public synchronized int getRecentRecallRank(UUID memoryId) {
        if (memoryId == null) {
            return -1;
        }
        int rank = 0;
        for (UUID id : recentRecalledMemoryIds) {
            if (memoryId.equals(id)) {
                return rank;
            }
            rank++;
        }
        return -1;
    }

    public synchronized List<UUID> getRecentRecalledMemoryIds() {
        return Collections.unmodifiableList(new ArrayList<>(recentRecalledMemoryIds));
    }

    private void trimRecentRecallQueue() {
        while (recentRecalledMemoryIds.size() > recentRecallWindow) {
            recentRecalledMemoryIds.removeLast();
        }
    }

    @Override
    public String toString() {
        return "AgentMemory{identity=" + (identity.length() > 30 ? identity.substring(0, 30) + "..." : identity)
                + ", actionBuffer=" + actionBuffer.size()
                + ", stmPool=" + stmPool
                + ", ltmRecords=" + ltmRecords.size()
                + ", recentRecall=" + recentRecalledMemoryIds.size() + "}";
    }
}
