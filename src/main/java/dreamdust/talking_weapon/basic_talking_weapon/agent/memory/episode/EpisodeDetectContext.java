package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单轮 detect 阶段传给 splitter 的上下文。
 * <p>
 * 大部分 splitter 只需要读 latest segment 的 signal；
 * 少数像 fallback 这样的 splitter，则可以额外读取：
 * <ul>
 * <li>当前已经排队的 pending marks</li>
 * <li>本轮前面其他 splitter 已经检测出的 marks</li>
 * <li>上一次成功加入 pending queue 的检测发生在哪一段</li>
 * </ul>
 */
public record EpisodeDetectContext(
        EpisodeTimeline timeline,
        FixedActionSegment latestSegment,
        List<EpisodePendingMark> detectedThisCycle,
        long lastAcceptedPendingDetectSegmentId) {

    public EpisodeDetectContext {
        timeline = timeline == null ? EpisodeTimeline.empty() : timeline;
        detectedThisCycle = detectedThisCycle == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(detectedThisCycle));
    }

    public boolean hasDetectedThisCycle() {
        return !detectedThisCycle.isEmpty();
    }

    public long latestSegmentId() {
        return latestSegment == null
                ? EpisodeTimeline.UNASSIGNED_SEGMENT_ID
                : latestSegment.getSegmentId();
    }
}
