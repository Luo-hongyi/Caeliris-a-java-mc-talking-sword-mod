package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * FixedActionSegment 附带的 splitter 消费信号。
 * <p>
 * 当前先承载：
 * <ul>
 * <li>intent 打分</li>
 * <li>该段中出现过的 action types</li>
 * </ul>
 */
public final class FixedSegmentSplitterSignals {

    private final SegmentIntentSignals intentSignals;
    private final Set<ActionType> observedActionTypes;

    public FixedSegmentSplitterSignals(
            SegmentIntentSignals intentSignals,
            Set<ActionType> observedActionTypes) {
        this.intentSignals = intentSignals == null ? SegmentIntentSignals.empty() : intentSignals;
        this.observedActionTypes = observedActionTypes == null || observedActionTypes.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(observedActionTypes));
    }

    public static FixedSegmentSplitterSignals fromSegment(ActionSegmentView segment) {
        return new FixedSegmentSplitterSignals(
                PlayerIntentScorer.analyze(segment),
                collectActionTypes(segment));
    }

    public SegmentIntentSignals getIntentSignals() {
        return intentSignals;
    }

    public Set<ActionType> getObservedActionTypes() {
        return observedActionTypes;
    }

    public boolean hasObservedAction(ActionType type) {
        return type != null && observedActionTypes.contains(type);
    }

    private static Set<ActionType> collectActionTypes(ActionSegmentView segment) {
        return segment == null ? Set.of() : segment.collectObservedActionTypes();
    }
}
