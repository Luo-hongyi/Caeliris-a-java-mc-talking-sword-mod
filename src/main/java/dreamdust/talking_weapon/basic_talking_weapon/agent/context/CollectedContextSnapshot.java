package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的上下文快照对象。
 * <p>
 * 用于承载“一次正式上下文采集”在记忆链路中的结果。
 * 默认 fixed segment 快照与后续 STM 并入的 context 都应尽量落入这一结构，
 * 以确保二者使用同一套 context 准入逻辑。
 */
public final class CollectedContextSnapshot {

    private static final CollectedContextSnapshot EMPTY =
            new CollectedContextSnapshot(0L, "empty", Map.of(), List.of(), List.of());

    private final long timestamp;
    private final String snapshotSource;
    private final Map<String, String> extras;
    private final List<TaggedContextEntry> taggedEntries;
    private final List<TaggedContextEntry> visibleTaggedEntries;

    public CollectedContextSnapshot(
            long timestamp,
            String snapshotSource,
            Map<String, String> extras,
            List<TaggedContextEntry> taggedEntries) {
        this(timestamp, snapshotSource, extras, taggedEntries, List.of());
    }

    public CollectedContextSnapshot(
            long timestamp,
            String snapshotSource,
            Map<String, String> extras,
            List<TaggedContextEntry> taggedEntries,
            List<TaggedContextEntry> visibleTaggedEntries) {
        this.timestamp = Math.max(0L, timestamp);
        this.snapshotSource = snapshotSource == null || snapshotSource.isBlank() ? "unknown" : snapshotSource;
        this.extras = extras == null || extras.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        this.taggedEntries = immutableMemoryEntries(taggedEntries);
        this.visibleTaggedEntries = immutableMemoryEntries(visibleTaggedEntries);
    }

    public static CollectedContextSnapshot empty() {
        return EMPTY;
    }

    public static CollectedContextSnapshot fromAgentContext(AgentContext context) {
        return fromAgentContext(context, List.of());
    }

    public static CollectedContextSnapshot fromAgentContext(
            AgentContext context,
            List<TaggedContextEntry> visibleTaggedEntries) {
        if (context == null) {
            return EMPTY;
        }
        return new CollectedContextSnapshot(
                context.getTimestamp(),
                context.getTriggerSource(),
                context.getExtras(),
                context.getTaggedEntries(),
                visibleTaggedEntries);
    }

    public static CollectedContextSnapshot fromEntries(
            long timestamp,
            String snapshotSource,
            List<TaggedContextEntry> taggedEntries) {
        return new CollectedContextSnapshot(timestamp, snapshotSource, Map.of(), taggedEntries, List.of());
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSnapshotSource() {
        return snapshotSource;
    }

    public Map<String, String> getExtras() {
        return extras;
    }

    public List<TaggedContextEntry> getTaggedEntries() {
        return taggedEntries;
    }

    public List<TaggedContextEntry> getVisibleTaggedEntries() {
        return visibleTaggedEntries;
    }

    public boolean isEmpty() {
        return extras.isEmpty() && taggedEntries.isEmpty();
    }

    public static List<TaggedContextEntry> memoryEligibleEntries(List<TaggedContextEntry> entries) {
        return immutableMemoryEntries(entries);
    }

    private static List<TaggedContextEntry> immutableMemoryEntries(List<TaggedContextEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        ArrayList<TaggedContextEntry> accepted = new ArrayList<>();
        for (TaggedContextEntry entry : entries) {
            if (entry == null || !entry.isMemoryEligible()) {
                continue;
            }
            accepted.add(entry);
        }
        if (accepted.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(accepted);
    }
}
