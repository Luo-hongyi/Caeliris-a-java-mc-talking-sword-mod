package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeDetectContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodePendingMark;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSplitPoint;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.List;
import java.util.Optional;

/**
 * 当 boundary queue 长时间没有新 mark 时，补一个低优先级兜底断点。
 * <p>
 * 它是当前唯一一个不直接消费 signal 的 splitter，
 * 而是消费 detect 上下文中的 mark 状态：
 * <ul>
 * <li>本轮前面 splitter 是否已经产出 mark</li>
 * <li>当前 pending queue 是否仍有候选边界</li>
 * <li>距离上一次成功加入 pending queue 已经过了多少段</li>
 * </ul>
 */
public final class FallbackQuietSplitter implements EpisodeSplitter {

    private static final String ID = "fallback_quiet";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<EpisodePendingMark> detect(EpisodeDetectContext context) {
        if (context == null || context.latestSegment() == null) {
            return List.of();
        }
        if (context.hasDetectedThisCycle()) {
            return List.of();
        }
        if (!context.timeline().pendingMarks().isEmpty()) {
            return List.of();
        }

        int quietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        int latestSegmentIndex = context.latestSegmentIndex();
        int lastAcceptedDetectIndex = context.lastAcceptedPendingDetectSegmentIndex();
        int quietSegments = lastAcceptedDetectIndex < 0
                ? latestSegmentIndex + 1
                : latestSegmentIndex - lastAcceptedDetectIndex;
        if (quietSegments < quietWindowSegments) {
            return List.of();
        }

        FixedActionSegment latestSegment = context.latestSegment();
        EpisodeSplitReason reason = EpisodeSplitReason.FALLBACK_QUIET_GAP;
        return List.of(EpisodePendingMark.fromRuntimeConfig(
                context.timeline(),
                latestSegmentIndex,
                latestSegment.getSegmentId(),
                latestSegmentIndex + 1,
                latestSegment.getEndTimestamp(),
                reason,
                id()));
    }

    @Override
    public boolean supports(EpisodePendingMark pendingMark) {
        return pendingMark != null && pendingMark.reason() == EpisodeSplitReason.FALLBACK_QUIET_GAP;
    }

    @Override
    public Optional<EpisodeSplitPoint> confirm(
            EpisodeTimeline timeline,
            EpisodePendingMark pendingMark,
            FixedActionSegment latestSegment,
            int latestSegmentIndex) {
        if (timeline == null || latestSegment == null || !supports(pendingMark)) {
            return Optional.empty();
        }

        EpisodeRegion previousRegion = timeline.regionBeforeSplit(pendingMark.splitSegmentIndex());
        EpisodeTimeline.BoundarySegmentIds boundarySegmentIds =
                timeline.boundarySegmentIdsAt(pendingMark.splitSegmentIndex());
        return Optional.of(new EpisodeSplitPoint(
                pendingMark.splitSegmentIndex(),
                boundarySegmentIds.leftSegmentId(),
                boundarySegmentIds.rightSegmentId(),
                pendingMark.splitTimestamp(),
                latestSegmentIndex,
                latestSegment.getSegmentId(),
                latestSegment.getEndTimestamp(),
                pendingMark.reason(),
                previousRegion != null ? previousRegion.primaryIntent() : null,
                previousRegion != null ? previousRegion.secondaryIntent() : null,
                null,
                null));
    }
}
