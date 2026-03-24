package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 动作时间段的只读视图。
 * <p>
 * fixed segment 与 moving prompt segment 都可以实现这组基础读取接口，
 * 这样意图评分等上层逻辑就不需要关心底层来源。
 */
public interface ActionSegmentView {

    long getStartTimestamp();

    long getEndTimestamp();

    int getRawStartIndex();

    int getRawEndIndex();

    List<? extends ActionSegmentEntryView> getEntries();

    default Set<ActionType> collectObservedActionTypes() {
        List<? extends ActionSegmentEntryView> entries = getEntries();
        if (entries == null || entries.isEmpty()) {
            return Set.of();
        }

        EnumSet<ActionType> types = EnumSet.noneOf(ActionType.class);
        for (ActionSegmentEntryView entry : entries) {
            if (entry != null && entry.getType() != null) {
                types.add(entry.getType());
            }
        }
        return types.isEmpty() ? Set.of() : Collections.unmodifiableSet(types);
    }

    default boolean hasObservedAction(ActionType type) {
        return type != null && collectObservedActionTypes().contains(type);
    }
}
