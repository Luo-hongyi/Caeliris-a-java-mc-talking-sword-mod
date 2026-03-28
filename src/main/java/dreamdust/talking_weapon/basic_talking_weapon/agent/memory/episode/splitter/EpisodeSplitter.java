package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeDetectContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTracker;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.List;
import java.util.Optional;

/**
 * Episode v2 中的规则 splitter。
 * <p>
 * splitter 只负责两件事：
 * <ul>
 * <li>基于最新 segment 检测 pending mark</li>
 * <li>对到期的 pending mark 做 confirm</li>
 * </ul>
 * <p>
 * 所有公共调度职责统一由 {@link EpisodeTracker} 负责。
 */
public interface EpisodeSplitter {

    String id();

    List<EpisodePendingMark> detect(EpisodeDetectContext context);

    boolean supports(EpisodePendingMark pendingMark);

    Optional<EpisodeSplitPoint> confirm(
            EpisodeTimeline timeline,
            EpisodePendingMark pendingMark,
            FixedActionSegment latestSegment,
            int latestTimelineIndex);
}
