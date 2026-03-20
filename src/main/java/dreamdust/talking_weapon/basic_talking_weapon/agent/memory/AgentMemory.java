package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final LinkedHashMap<UUID, Integer> recentRecallSuppressionTable;
    private int recentRecallWindow;
    private final RecallRuntimeState recallRuntimeState;

    public AgentMemory() {
        this.identity = "";
        this.actionBuffer = new ActionBuffer(64);
        this.stmPool = new STMPool();
        this.ltmRecords = new java.util.ArrayList<>();
        this.ltmRecordsCapacity = 100;
        this.recentRecallSuppressionTable = new LinkedHashMap<>();
        this.recentRecallWindow = 6;
        this.recallRuntimeState = new RecallRuntimeState();

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
        this.recentRecallWindow = Math.max(0, window);
        trimRecentRecallSuppressionTable();
    }

    public synchronized int getRecentRecallWindow() {
        return recentRecallWindow;
    }

    public synchronized void beginRecentRecallTurn() {
        if (recentRecallSuppressionTable.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> iterator = recentRecallSuppressionTable.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int remaining = Math.max(0, entry.getValue() - 1);
            if (remaining <= 0) {
                iterator.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    public synchronized void noteRecalledMemories(List<LTMEntry> recalledEntries) {
        if (recentRecallWindow <= 0) {
            recentRecallSuppressionTable.clear();
            return;
        }
        if (recalledEntries == null || recalledEntries.isEmpty()) {
            return;
        }
        for (LTMEntry entry : recalledEntries) {
            if (entry == null || entry.getId() == null) {
                continue;
            }
            recentRecallSuppressionTable.remove(entry.getId());
            recentRecallSuppressionTable.put(entry.getId(), recentRecallWindow + 1);
        }
        trimRecentRecallSuppressionTable();
    }

    public synchronized int getRecentRecallCooldown(UUID memoryId) {
        if (memoryId == null) {
            return 0;
        }
        return recentRecallSuppressionTable.getOrDefault(memoryId, 0);
    }

    public synchronized Map<UUID, Integer> getRecentRecallSuppressionTable() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(recentRecallSuppressionTable));
    }

    public RecallRuntimeState getRecallRuntimeState() {
        return recallRuntimeState;
    }

    private void trimRecentRecallSuppressionTable() {
        if (recentRecallSuppressionTable.isEmpty()) {
            return;
        }
        if (recentRecallWindow <= 0) {
            recentRecallSuppressionTable.clear();
            return;
        }
        int maxCooldown = recentRecallWindow + 1;
        Iterator<Map.Entry<UUID, Integer>> iterator = recentRecallSuppressionTable.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int clamped = Math.min(maxCooldown, Math.max(0, entry.getValue()));
            if (clamped <= 0) {
                iterator.remove();
            } else {
                entry.setValue(clamped);
            }
        }
    }

    @Override
    public String toString() {
        return "AgentMemory{identity=" + (identity.length() > 30 ? identity.substring(0, 30) + "..." : identity)
                + ", actionBuffer=" + actionBuffer.size()
                + ", stmPool=" + stmPool
                + ", ltmRecords=" + ltmRecords.size()
                + ", recentRecall=" + recentRecallSuppressionTable.size()
                + ", recallRuntimeState=true"
                + "}";
    }
}
