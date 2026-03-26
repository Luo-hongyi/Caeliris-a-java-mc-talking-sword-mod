package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.CollectedContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FixedActionSegment 附带的上下文与交互承载区。
 * <p>
 * 这里显式区分两层：
 * <ul>
 * <li>defaultContextEntries：segment 开始时采集的兜底环境快照</li>
 * <li>contextEntries：后续由 STM slice 融合进来的正式上下文；entries / visible entries 保持 merged 优先</li>
 * <li>contextExtras：supplementary extras 采用 default snapshot 兜底，再由 merged extras 覆盖同名 key</li>
 * </ul>
 */
public final class FixedActionSegmentContext {

    private static final FixedActionSegmentContext EMPTY =
            new FixedActionSegmentContext(
                    CollectedContextSnapshot.empty(),
                    CollectedContextSnapshot.empty(),
                    List.of());

    private final CollectedContextSnapshot defaultContextSnapshot;
    private final CollectedContextSnapshot mergedContextSnapshot;
    private final List<InteractionTurn> interactionTurns;

    public FixedActionSegmentContext(
            CollectedContextSnapshot defaultContextSnapshot,
            CollectedContextSnapshot mergedContextSnapshot,
            List<InteractionTurn> interactionTurns) {
        this.defaultContextSnapshot = defaultContextSnapshot == null
                ? CollectedContextSnapshot.empty()
                : defaultContextSnapshot;
        this.mergedContextSnapshot = mergedContextSnapshot == null
                ? CollectedContextSnapshot.empty()
                : mergedContextSnapshot;
        this.interactionTurns = immutableCopy(interactionTurns);
    }

    public static FixedActionSegmentContext empty() {
        return EMPTY;
    }

    public static FixedActionSegmentContext withDefaultSnapshot(CollectedContextSnapshot defaultContextSnapshot) {
        if (defaultContextSnapshot == null || defaultContextSnapshot.isEmpty()) {
            return EMPTY;
        }
        return new FixedActionSegmentContext(defaultContextSnapshot, CollectedContextSnapshot.empty(), List.of());
    }

    public CollectedContextSnapshot getDefaultContextSnapshot() {
        return defaultContextSnapshot;
    }

    public CollectedContextSnapshot getMergedContextSnapshot() {
        return mergedContextSnapshot;
    }

    public CollectedContextSnapshot getEffectiveContextSnapshot() {
        return mergedContextSnapshot.isEmpty() ? defaultContextSnapshot : mergedContextSnapshot;
    }

    public List<TaggedContextEntry> getDefaultContextEntries() {
        return defaultContextSnapshot.getTaggedEntries();
    }

    public List<TaggedContextEntry> getDefaultVisibleContextEntries() {
        return defaultContextSnapshot.getVisibleTaggedEntries();
    }

    public List<TaggedContextEntry> getMergedContextEntries() {
        return mergedContextSnapshot.getTaggedEntries();
    }

    public List<TaggedContextEntry> getMergedVisibleContextEntries() {
        return mergedContextSnapshot.getVisibleTaggedEntries();
    }

    public Map<String, String> getDefaultContextExtras() {
        return defaultContextSnapshot.getExtras();
    }

    public Map<String, String> getMergedContextExtras() {
        return mergedContextSnapshot.getExtras();
    }

    public Map<String, String> getEffectiveContextExtras() {
        Map<String, String> defaultExtras = defaultContextSnapshot.getExtras();
        Map<String, String> mergedExtras = mergedContextSnapshot.getExtras();
        if (defaultExtras.isEmpty()) {
            return mergedExtras;
        }
        if (mergedExtras.isEmpty()) {
            return defaultExtras;
        }

        LinkedHashMap<String, String> effective = new LinkedHashMap<>(defaultExtras);
        effective.putAll(mergedExtras);
        return Collections.unmodifiableMap(effective);
    }

    public List<TaggedContextEntry> getEffectiveContextEntries() {
        return getEffectiveContextSnapshot().getTaggedEntries();
    }

    public List<TaggedContextEntry> getEffectiveVisibleContextEntries() {
        if (!mergedContextSnapshot.getVisibleTaggedEntries().isEmpty()) {
            return mergedContextSnapshot.getVisibleTaggedEntries();
        }
        if (!defaultContextSnapshot.getVisibleTaggedEntries().isEmpty()) {
            return defaultContextSnapshot.getVisibleTaggedEntries();
        }
        return getEffectiveContextEntries();
    }

    public List<InteractionTurn> getInteractionTurns() {
        return interactionTurns;
    }

    public boolean usesDefaultContext() {
        return mergedContextSnapshot.isEmpty();
    }

    public boolean hasAnyContext() {
        return !defaultContextSnapshot.isEmpty() || !mergedContextSnapshot.isEmpty();
    }

    public FixedActionSegmentContext withMergedSnapshot(
            CollectedContextSnapshot mergedContextSnapshot,
            List<InteractionTurn> mergedInteractionTurns) {
        return new FixedActionSegmentContext(defaultContextSnapshot, mergedContextSnapshot, mergedInteractionTurns);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
