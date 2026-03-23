package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 面向 intent / episode / LTM 的原始动作归档层。
 * <p>
 * 与 {@link RecentActionBuffer} 不同，这里不做 TTL 淘汰，不做同类动作压缩，
 * 只保留按时间顺序写入的真实动作记录序列。
 * <p>
 * 为了避免内存无限增长，仍保留一个较大的环形容量上限。
 */
public final class ActionArchive {

    private final int capacity;
    private final ArrayDeque<ActionRecord> records;

    public ActionArchive(int capacity) {
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    /**
     * 追加一条动作记录。
     * <p>
     * 内部总是保存副本，避免与其他动作层共享可变对象。
     */
    public synchronized void add(ActionRecord record) {
        if (record == null) {
            return;
        }
        records.addLast(record.copy());
        while (records.size() > capacity) {
            records.pollFirst();
        }
    }

    /**
     * 返回当前所有动作记录的不可变快照（按时间顺序）。
     */
    public synchronized List<ActionRecord> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    /**
     * 返回基于当前原始动作序列计算出的统一时间段压缩视图。
     * <p>
     * 该视图是派生结果，不会改写内部保存的原始动作时间线。
     */
    public synchronized List<ActionCompactedSegment> snapshotCompacted() {
        return ActionArchiveCompactor.compact(new ArrayList<>(records));
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized boolean isEmpty() {
        return records.isEmpty();
    }

    public synchronized void clear() {
        records.clear();
    }
}
