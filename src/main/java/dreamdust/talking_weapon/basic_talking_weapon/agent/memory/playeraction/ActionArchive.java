package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 面向 intent / episode / LTM 的原始动作归档层。
 * <p>
 * 与 prompt 侧的 {@link MovingActionBuffer} 不同，这里不做 TTL 淘汰，不做同类动作压缩，
 * 只保留按时间顺序写入的真实动作记录序列。
 * <p>
 * 为了避免内存无限增长，仍保留一个较大的环形容量上限。
 */
public final class ActionArchive {

    private final int capacity;
    private final ArrayDeque<ActionRecord> records;
    private final FixedActionSegmentBuffer fixedSegmentBuffer;

    public ActionArchive(int capacity) {
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
        this.fixedSegmentBuffer = new FixedActionSegmentBuffer();
    }

    /**
     * 追加一条动作记录。
     * <p>
     * 内部总是保存副本，避免与其他动作层共享可变对象。
     */
    public synchronized FixedSegmentUpdate add(ActionRecord record) {
        if (record == null) {
            return FixedSegmentUpdate.none();
        }

        ActionRecord copy = record.copy();
        records.addLast(copy);
        while (records.size() > capacity) {
            records.pollFirst();
        }

        long fixedSegmentMs = ActionTimelineRuntimeSettings.getFixedSegmentMs();
        long now = Math.max(System.currentTimeMillis(), copy.getTimestamp());
        if (fixedSegmentBuffer.needsRebuild(fixedSegmentMs)) {
            fixedSegmentBuffer.rebuild(new ArrayList<>(records), fixedSegmentMs, now);
            return FixedSegmentUpdate.replaceAll(fixedSegmentBuffer.snapshotActiveSealedSegments());
        }

        return fixedSegmentBuffer.append(copy, records.size() - 1, fixedSegmentMs, now);
    }

    /**
     * 返回当前所有动作记录的不可变快照（按时间顺序）。
     */
    public synchronized List<ActionRecord> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    /**
     * 丢弃某个时间区间内的原始动作记录，并以剩余记录重建 fixed segments。
     * <p>
     * 语义固定为删除 `[startInclusiveTimestamp, endExclusiveTimestamp)` 内的 raw records。
     */
    public synchronized FixedSegmentUpdate discardBetween(long startInclusiveTimestamp, long endExclusiveTimestamp) {
        long safeStart = Math.max(0L, startInclusiveTimestamp);
        if (endExclusiveTimestamp <= safeStart || records.isEmpty()) {
            return FixedSegmentUpdate.none();
        }

        int beforeSize = records.size();
        records.removeIf(record ->
                record != null
                        && record.getTimestamp() >= safeStart
                        && record.getTimestamp() < endExclusiveTimestamp);
        if (records.size() == beforeSize) {
            return FixedSegmentUpdate.none();
        }

        long fixedSegmentMs = ActionTimelineRuntimeSettings.getFixedSegmentMs();
        long now = System.currentTimeMillis();
        fixedSegmentBuffer.rebuild(new ArrayList<>(records), fixedSegmentMs, now);
        return FixedSegmentUpdate.replaceAll(fixedSegmentBuffer.snapshotActiveSealedSegments());
    }

    /**
     * 仅从内存中的 raw action archive 裁剪指定时间窗，不立刻重建 fixed segments。
     * <p>
     * 用于和 discard window 配合：raw 真源被裁掉，physical fixed segments 先保留到后续 compact。
     */
    public synchronized int discardRawRecordsBetween(long startInclusiveTimestamp, long endExclusiveTimestamp) {
        long safeStart = Math.max(0L, startInclusiveTimestamp);
        if (endExclusiveTimestamp <= safeStart || records.isEmpty()) {
            return 0;
        }
        int beforeSize = records.size();
        records.removeIf(record ->
                record != null
                        && record.getTimestamp() >= safeStart
                        && record.getTimestamp() < endExclusiveTimestamp);
        return Math.max(0, beforeSize - records.size());
    }

    /**
     * 在 fixed segment 层登记一段 discard window，并返回更新后的 active fixed segment 视图。
     */
    public synchronized FixedSegmentUpdate registerDiscardWindow(
            long startSegmentId,
            long endSegmentId,
            long startTimestamp,
            long endTimestamp) {
        if (startSegmentId <= 0L || endSegmentId <= 0L) {
            return FixedSegmentUpdate.none();
        }
        boolean registered = fixedSegmentBuffer.registerDiscardWindow(new FixedSegmentDiscardWindow(
                startSegmentId,
                endSegmentId,
                Math.max(0L, startTimestamp),
                Math.max(Math.max(0L, startTimestamp), endTimestamp),
                Instant.now().toEpochMilli()));
        if (!registered) {
            return FixedSegmentUpdate.none();
        }
        return FixedSegmentUpdate.replaceAll(fixedSegmentBuffer.snapshotActiveSealedSegments());
    }

    /**
     * 启动恢复后，将已经落盘的 discard windows 物理压进当前 sealed segments。
     * <p>
     * 这里之所以安全，是因为 raw action archive 本身不会跨重启恢复；
     * 因此启动期 compact 后，不会再被旧 raw records 重新长回来。
     */
    public synchronized FixedSegmentUpdate compactDiscardedSegments() {
        boolean compacted = fixedSegmentBuffer.compactDiscardedSegments();
        if (!compacted) {
            return FixedSegmentUpdate.none();
        }
        return FixedSegmentUpdate.replaceAll(fixedSegmentBuffer.snapshotActiveSealedSegments());
    }

    /**
     * 返回当前已经 sealed 的 fixed segments。
     * <p>
     * 当前仍在累加中的 open window 不会出现在这里；
     * 它只有在跨窗或时间到期后 seal，才会进入正式 fixed segment 序列。
     */
    public synchronized List<FixedActionSegment> snapshotFixedSegments() {
        return snapshotFixedSegments(ActionTimelineRuntimeSettings.getFixedSegmentMs());
    }

    public synchronized List<FixedActionSegment> snapshotFixedSegments(long fixedSegmentMs) {
        long now = System.currentTimeMillis();
        if (fixedSegmentBuffer.needsRebuild(fixedSegmentMs)) {
            fixedSegmentBuffer.rebuild(new ArrayList<>(records), fixedSegmentMs, now);
        } else {
            fixedSegmentBuffer.sealElapsedSegments(now);
        }
        return fixedSegmentBuffer.snapshotActiveSealedSegments();
    }

    public synchronized FixedSegmentBufferRuntimeState snapshotFixedSegmentRuntimeState() {
        return snapshotFixedSegmentRuntimeState(ActionTimelineRuntimeSettings.getFixedSegmentMs());
    }

    public synchronized FixedSegmentBufferRuntimeState snapshotFixedSegmentRuntimeState(long fixedSegmentMs) {
        long now = System.currentTimeMillis();
        if (fixedSegmentBuffer.needsRebuild(fixedSegmentMs)) {
            fixedSegmentBuffer.rebuild(new ArrayList<>(records), fixedSegmentMs, now);
        } else {
            fixedSegmentBuffer.sealElapsedSegments(now);
        }
        return fixedSegmentBuffer.snapshotRuntimeState();
    }

    public synchronized void restoreFixedSegmentRuntimeState(FixedSegmentBufferRuntimeState runtimeState) {
        if (runtimeState == null || runtimeState.isEmpty()) {
            return;
        }
        long fixedSegmentMs = ActionTimelineRuntimeSettings.getFixedSegmentMs();
        if (runtimeState.getFixedSegmentMs() != fixedSegmentMs) {
            return;
        }
        fixedSegmentBuffer.restoreRuntimeState(runtimeState);
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized boolean isEmpty() {
        return records.isEmpty();
    }

    public synchronized void clear() {
        records.clear();
        fixedSegmentBuffer.clear();
    }
}
