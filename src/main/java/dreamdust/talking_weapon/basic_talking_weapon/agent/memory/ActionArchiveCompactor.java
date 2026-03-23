package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ActionArchive 的通用压缩器。
 * <p>
 * 压缩规则分两层：
 * <ol>
 * <li>先按固定时间窗口切成全局时间段</li>
 * <li>再在每个时间段内，仅合并同类型且同目标的动作</li>
 * </ol>
 * <p>
 * 这样可以让不同动作共享同一组稳定时间边界，同时允许同类动作被其他动作打断后继续在同段内累加。
 */
public final class ActionArchiveCompactor {

    /** 固定时间窗长度：默认 1 分钟一段。 */
    public static final long DEFAULT_FIXED_SEGMENT_MS = 60_000L;

    private ActionArchiveCompactor() {
    }

    public static List<ActionCompactedSegment> compact(List<ActionRecord> records) {
        return compact(records, DEFAULT_FIXED_SEGMENT_MS);
    }

    public static List<ActionCompactedSegment> compact(List<ActionRecord> records, long fixedSegmentMs) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        long effectiveSegmentMs = Math.max(1L, fixedSegmentMs);
        List<ActionCompactedSegment> segments = new ArrayList<>();
        int segmentStartIndex = 0;
        long firstTimestamp = safeTimestamp(records.get(0));
        long currentWindowIndex = windowIndex(firstTimestamp, effectiveSegmentMs);

        for (int i = 1; i < records.size(); i++) {
            long currentTimestamp = safeTimestamp(records.get(i));
            long currentRecordWindowIndex = windowIndex(currentTimestamp, effectiveSegmentMs);
            if (currentRecordWindowIndex == currentWindowIndex) {
                continue;
            }

            segments.add(buildSegment(records, segmentStartIndex, i - 1, currentWindowIndex, effectiveSegmentMs));
            segmentStartIndex = i;
            currentWindowIndex = currentRecordWindowIndex;
        }

        segments.add(buildSegment(records, segmentStartIndex, records.size() - 1, currentWindowIndex, effectiveSegmentMs));
        return List.copyOf(segments);
    }

    private static ActionCompactedSegment buildSegment(
            List<ActionRecord> records,
            int startIndex,
            int endIndex,
            long windowIndex,
            long fixedSegmentMs) {
        Map<EntryKey, MutableEntry> aggregated = new LinkedHashMap<>();
        for (int i = startIndex; i <= endIndex; i++) {
            ActionRecord record = records.get(i);
            EntryKey key = new EntryKey(record.getType(), record.getTarget());
            MutableEntry existing = aggregated.get(key);
            if (existing == null) {
                aggregated.put(key, new MutableEntry(record, i));
            } else {
                existing.merge(record, i);
            }
        }

        List<ActionCompactedEntry> entries = new ArrayList<>(aggregated.size());
        for (MutableEntry entry : aggregated.values()) {
            entries.add(entry.freeze());
        }

        long windowStartTimestamp = windowIndex * fixedSegmentMs;
        long windowEndTimestamp = windowStartTimestamp + fixedSegmentMs;
        return new ActionCompactedSegment(
                windowStartTimestamp,
                windowEndTimestamp,
                startIndex,
                endIndex,
                entries);
    }

    private static long safeTimestamp(ActionRecord record) {
        return record == null ? 0L : Math.max(0L, record.getTimestamp());
    }

    private static long windowIndex(long timestamp, long fixedSegmentMs) {
        long clampedTimestamp = Math.max(0L, timestamp);
        return clampedTimestamp / fixedSegmentMs;
    }

    private record EntryKey(ActionType type, String target) {
    }

    private static final class MutableEntry {
        private final ActionType type;
        private final String target;
        private int count;
        private long firstTimestamp;
        private long lastTimestamp;
        private int rawStartIndex;
        private int rawEndIndex;
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();

        private MutableEntry(ActionRecord record, int rawIndex) {
            this.type = record.getType();
            this.target = record.getTarget();
            this.count = Math.max(1, record.getCount());
            this.firstTimestamp = safeTimestamp(record);
            this.lastTimestamp = safeTimestamp(record);
            this.rawStartIndex = rawIndex;
            this.rawEndIndex = rawIndex;
            this.detailTags.addAll(record.getDetailTags());
        }

        private void merge(ActionRecord record, int rawIndex) {
            if (record == null || record.getType() != type || !Objects.equals(record.getTarget(), target)) {
                return;
            }
            this.count += Math.max(1, record.getCount());
            this.lastTimestamp = Math.max(this.lastTimestamp, safeTimestamp(record));
            this.rawEndIndex = rawIndex;
            this.detailTags.addAll(record.getDetailTags());
        }

        private ActionCompactedEntry freeze() {
            return new ActionCompactedEntry(
                    type,
                    target,
                    count,
                    firstTimestamp,
                    lastTimestamp,
                    rawStartIndex,
                    rawEndIndex,
                    detailTags);
        }
    }
}
