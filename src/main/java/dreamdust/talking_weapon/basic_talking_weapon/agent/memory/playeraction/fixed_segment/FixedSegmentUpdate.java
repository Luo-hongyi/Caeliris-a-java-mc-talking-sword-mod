package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ActionArchive 向 episode 层汇报的 fixed segment 更新结果。
 * <p>
 * `replaceAll=true` 表示下游应以整份 segments 覆盖旧状态；
 * 否则表示只是追加了新 sealed segments。
 */
public record FixedSegmentUpdate(
        boolean replaceAll,
        List<FixedActionSegment> segments) {

    private static final FixedSegmentUpdate NONE = new FixedSegmentUpdate(false, List.of());

    public FixedSegmentUpdate {
        segments = segments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public static FixedSegmentUpdate none() {
        return NONE;
    }

    public static FixedSegmentUpdate append(FixedActionSegment segment) {
        if (segment == null) {
            return none();
        }
        return new FixedSegmentUpdate(false, List.of(segment));
    }

    public static FixedSegmentUpdate append(List<FixedActionSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return none();
        }
        return new FixedSegmentUpdate(false, segments);
    }

    public static FixedSegmentUpdate replaceAll(List<FixedActionSegment> segments) {
        return new FixedSegmentUpdate(true, segments);
    }

    public boolean hasChanges() {
        return replaceAll || !segments.isEmpty();
    }
}
