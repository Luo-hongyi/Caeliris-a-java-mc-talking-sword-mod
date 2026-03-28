package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

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
            return FixedSegmentUpdate.replaceAll(fixedSegmentBuffer.snapshotSealedSegments());
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
        return fixedSegmentBuffer.snapshotSealedSegments();
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
