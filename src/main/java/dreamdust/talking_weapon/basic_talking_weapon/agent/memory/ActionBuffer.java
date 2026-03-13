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

    /** 动作有效期窗口 (毫秒)，超过此时间的记录将被自动清理 (默认 3 分钟) */
    private static final long TIME_WINDOW_MS = 180_000L;

    private final int capacity;
    private final ArrayDeque<ActionRecord> buffer;

    public ActionBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    private void evictStale() {
        long now = System.currentTimeMillis();
        while (!buffer.isEmpty()) {
            ActionRecord oldest = buffer.peekFirst();
            if (now - oldest.getTimestamp() > TIME_WINDOW_MS) {
                buffer.pollFirst();
            } else {
                break;
            }
        }
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
        evictStale();

        boolean merged = false;

        // KILL 覆盖整个缓冲区的同目标 ATTACK，因为目标已死
        if (record.getType() == ActionType.KILL && record.getTarget() != null) {
            buffer.removeIf(r -> r.getType() == ActionType.ATTACK && record.getTarget().equals(r.getTarget()));
        }

        // 尝试合并缓冲区内完全一样的 ActionType 和 Target (解决交替动作记录不合并的问题)
        for (ActionRecord existing : buffer) {
            if (existing.getType() == record.getType() && Objects.equals(existing.getTarget(), record.getTarget())) {
                existing.incrementCount();
                existing.updateTimestamp(); // TTL 刷新

                // 将被合并的记录移到队尾，表示它是最新发生的事件
                buffer.remove(existing);
                buffer.addLast(existing);
                merged = true;
                break;
            }
        }

        if (!merged) {
            buffer.addLast(record);
            while (buffer.size() > capacity) {
                buffer.pollFirst();
            }
        }
    }

    // ─────────────────────────────────────────────
    // 读取
    // ─────────────────────────────────────────────

    /**
     * 返回当前所有记录的不可变快照（按时间顺序）。
     */
    public synchronized List<ActionRecord> snapshot() {
        evictStale();
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    /**
     * 当前缓冲区大小。
     */
    public synchronized int size() {
        evictStale();
        return buffer.size();
    }

    /**
     * 缓冲区是否为空。
     */
    public synchronized boolean isEmpty() {
        evictStale();
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

    /**
     * 将当前缓冲区内的所有有效记录转移到目标缓冲区中，并清空当前缓冲区。
     * 用于阅后即焚（转移到已读池）。
     */
    public synchronized void drainTo(ActionBuffer target) {
        evictStale();
        for (ActionRecord record : buffer) {
            target.add(record);
        }
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
        evictStale();
        if (buffer.isEmpty())
            return "(无近期动作)";

        // 按 ActionType 分组，再按 target 聚合计数
        Map<ActionType, Map<String, Integer>> grouped = new LinkedHashMap<>();
        for (ActionRecord r : buffer) {
            grouped.computeIfAbsent(r.getType(), k -> new LinkedHashMap<>())
                    .merge(r.getTarget() != null ? r.getTarget() : "", r.getCount(), Integer::sum);
        }

        List<String> summaries = new ArrayList<>();
        for (Map.Entry<ActionType, Map<String, Integer>> entry : grouped.entrySet()) {
            ActionType type = entry.getKey();
            Map<String, Integer> targets = entry.getValue();

            // 如果放置/破坏的方块种类超过 5 种，进行折叠概括（防止建造房子时记录过长）
            if (targets.size() > 5 && (type == ActionType.MINE_BLOCK || type == ActionType.PLACE_BLOCK)) {
                int totalCount = targets.values().stream().mapToInt(Integer::intValue).sum();
                List<String> topTargets = targets.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .limit(5)
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
                summaries.add(type.name() + " 各种方块 ×" + totalCount + " (含 " + String.join(", ", topTargets) + " 等)");
            } else {
                for (Map.Entry<String, Integer> tEntry : targets.entrySet()) {
                    String target = tEntry.getKey();
                    int count = tEntry.getValue();
                    String key = type.name() + (target.isEmpty() ? "" : " " + target);
                    summaries.add(count > 1 ? key + " ×" + count : key);
                }
            }
        }

        return String.join(", ", summaries);
    }
}
