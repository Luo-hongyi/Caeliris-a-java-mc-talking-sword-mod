package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FixedActionSegmentBuffer 的正式跨重启运行时状态。
 * <p>
 * 这里承载的不是调试快照，而是 buffer 恢复 sealed/open segment 与 segmentId 发号进度所需的最小状态。
 */
public final class FixedSegmentBufferRuntimeState {

    private static final FixedSegmentBufferRuntimeState EMPTY =
            new FixedSegmentBufferRuntimeState(-1L, 1L, List.of(), null, List.of());

    private final long fixedSegmentMs;
    private final long nextSegmentId;
    private final List<FixedActionSegment> sealedSegments;
    private final FixedActionSegment openSegment;
    private final List<FixedSegmentDiscardWindow> discardWindows;

    public FixedSegmentBufferRuntimeState(
            long fixedSegmentMs,
            long nextSegmentId,
            List<FixedActionSegment> sealedSegments,
            FixedActionSegment openSegment,
            List<FixedSegmentDiscardWindow> discardWindows) {
        this.fixedSegmentMs = fixedSegmentMs;
        this.nextSegmentId = Math.max(1L, nextSegmentId);
        this.sealedSegments = sealedSegments == null || sealedSegments.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(sealedSegments));
        this.openSegment = openSegment;
        this.discardWindows = discardWindows == null || discardWindows.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(discardWindows));
    }

    public static FixedSegmentBufferRuntimeState empty() {
        return EMPTY;
    }

    public long getFixedSegmentMs() {
        return fixedSegmentMs;
    }

    public long getNextSegmentId() {
        return nextSegmentId;
    }

    public List<FixedActionSegment> getSealedSegments() {
        return sealedSegments;
    }

    public FixedActionSegment getOpenSegment() {
        return openSegment;
    }

    public List<FixedSegmentDiscardWindow> getDiscardWindows() {
        return discardWindows;
    }

    public boolean isEmpty() {
        return fixedSegmentMs <= 0L
                && sealedSegments.isEmpty()
                && openSegment == null
                && discardWindows.isEmpty()
                && nextSegmentId <= 1L;
    }
}
