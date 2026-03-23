package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 episode intent 分析的总输出。
 */
public record EpisodeIntentTimeline(
        int sourceSegmentCount,
        List<EpisodeIntentRegion> regions,
        List<EpisodeIntentSplitPoint> splitPoints) {

    public EpisodeIntentTimeline {
        regions = regions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(regions));
        splitPoints = splitPoints == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(splitPoints));
    }

    public boolean hasSplits() {
        return !splitPoints.isEmpty();
    }

    public EpisodeIntentSplitPoint latestSplitPoint() {
        return splitPoints.isEmpty() ? null : splitPoints.get(splitPoints.size() - 1);
    }
}
