package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimelineStore;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionArchive;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedSegmentUpdate;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.MovingActionBuffer;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeActionTimelineFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 记忆系统。
 * <ol>
 * <li><b>Identity</b> — 人格身份 (system prompt)，永久不变</li>
 * <li><b>MovingActionBuffer</b> — prompt / 近期感知层的 moving segment 缓冲</li>
 * <li><b>ActionArchive</b> — 面向 intent / episode / LTM 的原始动作记录流</li>
 * <li><b>STMPool</b> — 当前纪元的短期记忆聚合池</li>
 * <li><b>LTMRecords</b> — 剑视角的深度记忆切片列表 / 偏好设定</li>
 * </ol>
 * <p>
 * 线程安全说明：MovingActionBuffer / ActionArchive 自身线程安全；ltmRecords
 * 的访问集中在 AgentRuntime 的 worker 线程中，调用方确保不并发写。
 */
public class AgentMemory {

    private final Object sourceMaterialLock = new Object();

    // ═══════════════════════════════════════════════════════════════
    // Layer 0: Identity（永久）
    // ═══════════════════════════════════════════════════════════════

    private String identity;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1: MovingActionBuffer（prompt moving segment 缓冲）
    // ═══════════════════════════════════════════════════════════════

    private final MovingActionBuffer movingActionBuffer;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1.1: ActionArchive（原始动作归档）
    // ═══════════════════════════════════════════════════════════════

    private final ActionArchive actionArchive;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1.5: STMPool (短期记忆聚合池)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 短期记忆聚合池：存放当前纪元内的全量上下文条目和 Agent 自身产出。
     * 供 LTM 合成任务读取，合成完成后清空。
     */
    private final STMPool stmPool;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1.6: EpisodeTimelineStore（episode 正式状态中心）
    // ═══════════════════════════════════════════════════════════════

    /**
     * episode 正式材料中心：
     * 持有 fixed segments / pending marks / split points 的最近一次分析结果。
     * <p>
     * 当前先作为统一状态中心接入；
     * 后续 segment 级 STM 合并与持久化也应优先挂在这里。
     */
    private final EpisodeTimelineStore episodeTimelineStore;

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
        this.movingActionBuffer = new MovingActionBuffer();
        this.actionArchive = new ActionArchive(4096);
        this.actionArchive.restoreFixedSegmentRuntimeState(
                StudioRuntimeActionTimelineFile.readPersistedFixedSegmentRuntimeState());
        this.stmPool = new STMPool();
        this.episodeTimelineStore = new EpisodeTimelineStore();
        applyFixedSegmentUpdate(this.actionArchive.compactDiscardedSegments());
        this.episodeTimelineStore.replaceFixedSegments(this.actionArchive.snapshotFixedSegments());
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

    // ── MovingActionBuffer / ActionArchive ──────────────────────

    public MovingActionBuffer getMovingActionBuffer() {
        return movingActionBuffer;
    }

    public ActionArchive getActionArchive() {
        return actionArchive;
    }

    /**
     * 将同一条观察到的动作同时写入“prompt 近期缓冲”和“长期分析归档”。
     */
    public void recordObservedAction(ActionRecord record) {
        if (record == null) {
            return;
        }
        synchronized (sourceMaterialLock) {
            FixedSegmentUpdate fixedSegmentUpdate = actionArchive.add(record);
            applyFixedSegmentUpdate(fixedSegmentUpdate);
            movingActionBuffer.add(record.copy());
        }
    }

    private void applyFixedSegmentUpdate(FixedSegmentUpdate update) {
        if (update == null || !update.hasChanges()) {
            return;
        }
        if (update.replaceAll()) {
            episodeTimelineStore.replaceFixedSegments(update.segments());
            return;
        }
        episodeTimelineStore.appendFixedSegments(update.segments());
    }

    public STMPool getSTMPool() {
        return stmPool;
    }

    public EpisodeTimelineStore getEpisodeTimelineStore() {
        return episodeTimelineStore;
    }

    /**
     * source material 的统一 owner-side cleanup 入口。
     * <p>
     * 当前先统一串起：
     * - raw action archive 的内存裁剪
     * - fixed segment discard window 登记
     * - episode timeline replace-all(active fixed segments)
     * - STM local discard
     * <p>
     * 后续 fixed segment tombstone / persisted discard state 也应继续收口在这条主路径。
     */
    public MemorySourceMaterialCleanupResult cleanupSourceMaterial(MemorySourceMaterialCleanupPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }

        synchronized (sourceMaterialLock) {
            MemorySourceMaterialWindow resolvedWindow = resolveActiveCleanupWindow(plan.window());
            if (resolvedWindow == null) {
                return MemorySourceMaterialCleanupResult.stale(plan);
            }

            int beforeRawActionCount = actionArchive.size();
            int beforeEpisodeSegmentCount = episodeTimelineStore.snapshot().segmentCount();
            int beforeStmContextCount = stmPool.contextSize();
            int beforeStmInteractionCount = stmPool.turnSize();

            int removedRawActionCount = actionArchive.discardRawRecordsBetween(
                    resolvedWindow.startTimestamp(),
                    resolvedWindow.endTimestamp());
            FixedSegmentUpdate fixedSegmentUpdate = actionArchive.registerDiscardWindow(
                    resolvedWindow.startSegmentId(),
                    resolvedWindow.endSegmentId(),
                    resolvedWindow.startTimestamp(),
                    resolvedWindow.endTimestamp());
            applyFixedSegmentUpdate(fixedSegmentUpdate);

            stmPool.discardBetween(
                    resolvedWindow.stmSliceStartTimestamp(),
                    resolvedWindow.stmSliceExclusiveTimestamp());

            int removedEpisodeSegmentCount = Math.max(0, beforeEpisodeSegmentCount - episodeTimelineStore.snapshot().segmentCount());
            int removedStmContextCount = Math.max(0, beforeStmContextCount - stmPool.contextSize());
            int removedStmInteractionCount = Math.max(0, beforeStmInteractionCount - stmPool.turnSize());

            return new MemorySourceMaterialCleanupResult(
                    plan,
                    false,
                    removedRawActionCount,
                    removedEpisodeSegmentCount,
                    removedStmContextCount,
                    removedStmInteractionCount);
        }
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

    private MemorySourceMaterialWindow resolveActiveCleanupWindow(MemorySourceMaterialWindow requestedWindow) {
        if (requestedWindow == null) {
            return null;
        }

        EpisodeTimeline timeline = episodeTimelineStore.snapshot();
        FixedActionSegment startSegment = timeline.getSegmentById(requestedWindow.startSegmentId());
        FixedActionSegment endSegment = timeline.getSegmentById(requestedWindow.endSegmentId());
        if (startSegment == null || endSegment == null) {
            return null;
        }

        if (startSegment.getStartTimestamp() != requestedWindow.startTimestamp()
                || endSegment.getEndTimestamp() != requestedWindow.endTimestamp()) {
            return null;
        }

        int startIndex = timeline.indexOfSegmentId(requestedWindow.startSegmentId());
        int endIndex = timeline.indexOfSegmentId(requestedWindow.endSegmentId());
        if (startIndex < 0 || endIndex < startIndex) {
            return null;
        }

        return new MemorySourceMaterialWindow(
                startSegment.getSegmentId(),
                endSegment.getSegmentId(),
                startSegment.getStartTimestamp(),
                endSegment.getEndTimestamp(),
                requestedWindow.stmSliceStartTimestamp(),
                requestedWindow.stmSliceExclusiveTimestamp());
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
                + ", movingActionBuffer=" + movingActionBuffer.segmentCount()
                + ", actionArchive=" + actionArchive.size()
                + ", stmPool=" + stmPool
                + ", ltmRecords=" + ltmRecords.size()
                + ", recentRecall=" + recentRecallSuppressionTable.size()
                + ", recallRuntimeState=true"
                + "}";
    }
}
