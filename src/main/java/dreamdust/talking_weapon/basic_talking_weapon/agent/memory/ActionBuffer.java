package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 线程安全的环形动作缓冲区。
 * <p>
 * 存储玩家的高频游戏动作（攻击、挖矿、旅行等），不直接触发 LLM。
 * 后台 Trigger / ReflectionTask 定期读取快照并消费。
 * <p>
 * <b>压缩策略</b>：{@link #add(ActionRecord)} 时，若缓冲尾部有可合并的记录
 * （同类型 + 同目标 + 5秒内），则递增计数而非插入新条目。
 */
public final class ActionBuffer {

    /** 尾部合并时间窗口 (毫秒) */
    private static final long MERGE_GAP_MS = 5000L;

    private final int capacity;
    private final ArrayDeque<ActionRecord> buffer;

    public ActionBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    // ─────────────────────────────────────────────
    // 写入
    // ─────────────────────────────────────────────

    /**
     * 添加一条动作记录，自动尾部合并压缩。
     * <p>
     * 如果尾部最后一条记录可以合并（{@link ActionRecord#canMergeWith}），
     * 则仅递增计数；否则正常入队，超容量时淘汰最早的记录。
     */
    public synchronized void add(ActionRecord record) {
        if (!buffer.isEmpty()) {
            ActionRecord tail = buffer.peekLast();
            if (tail != null && tail.canMergeWith(record, MERGE_GAP_MS)) {
                tail.incrementCount();
                return;
            }
        }

        // KILL 覆盖同目标的尾部 ATTACK
        if (record.getType() == ActionType.KILL && !buffer.isEmpty()) {
            ActionRecord tail = buffer.peekLast();
            if (tail != null
                    && tail.getType() == ActionType.ATTACK
                    && Objects.equals(tail.getTarget(), record.getTarget())) {
                buffer.pollLast(); // 移除被覆盖的 ATTACK
            }
        }

        buffer.addLast(record);
        while (buffer.size() > capacity) {
            buffer.pollFirst();
        }
    }

    // ─────────────────────────────────────────────
    // 读取
    // ─────────────────────────────────────────────

    /**
     * 返回当前所有记录的不可变快照（按时间顺序）。
     */
    public synchronized List<ActionRecord> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    /**
     * 当前缓冲区大小。
     */
    public synchronized int size() {
        return buffer.size();
    }

    /**
     * 缓冲区是否为空。
     */
    public synchronized boolean isEmpty() {
        return buffer.isEmpty();
    }

    // ─────────────────────────────────────────────
    // 清空
    // ─────────────────────────────────────────────

    /**
     * 清空缓冲区（反思任务完成后调用）。
     */
    public synchronized void clear() {
        buffer.clear();
    }

    // ─────────────────────────────────────────────
    // 摘要生成
    // ─────────────────────────────────────────────

    /**
     * 生成精简的文本摘要，用于注入 LLM Prompt。
     * <p>
     * 规则：
     * <ol>
     * <li>同 ActionType + target 只出现一次（计数合并）</li>
     * <li>KILL 覆盖同目标的 ATTACK</li>
     * <li>稀有事件（KILL, DEATH, DIM_CHANGE）始终保留</li>
     * </ol>
     *
     * @return 如 "击杀 zombie ×5, 挖掘 diamond_ore ×2, 长途旅行"
     */
    public synchronized String toContextSummary() {
        if (buffer.isEmpty())
            return "(无近期动作)";

        // 按 type+target 聚合计数
        Map<String, Integer> aggregated = new LinkedHashMap<>();
        Set<String> killedTargets = new HashSet<>();

        for (ActionRecord r : buffer) {
            String key = r.getType().name() + (r.getTarget() != null ? " " + r.getTarget() : "");
            aggregated.merge(key, r.getCount(), Integer::sum);
            if (r.getType() == ActionType.KILL && r.getTarget() != null) {
                killedTargets.add(r.getTarget());
            }
        }

        // 去除被 KILL 覆盖的 ATTACK
        for (String killed : killedTargets) {
            aggregated.remove("ATTACK " + killed);
        }

        return aggregated.entrySet().stream()
                .map(e -> e.getValue() > 1 ? e.getKey() + " ×" + e.getValue() : e.getKey())
                .collect(Collectors.joining(", "));
    }
}
