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
            new FixedSegmentBufferRuntimeState(-1L, 1L, List.of(), null);

    private final long fixedSegmentMs;
    private final long nextSegmentId;
    private final List<FixedActionSegment> sealedSegments;
    private final FixedActionSegment openSegment;

    public FixedSegmentBufferRuntimeState(
            long fixedSegmentMs,
            long nextSegmentId,
            List<FixedActionSegment> sealedSegments,
            FixedActionSegment openSegment) {
        this.fixedSegmentMs = fixedSegmentMs;
        this.nextSegmentId = Math.max(1L, nextSegmentId);
        this.sealedSegments = sealedSegments == null || sealedSegments.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(sealedSegments));
        this.openSegment = openSegment;
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

    public boolean isEmpty() {
        return fixedSegmentMs <= 0L
                && sealedSegments.isEmpty()
                && openSegment == null
                && nextSegmentId <= 1L;
    }
}
