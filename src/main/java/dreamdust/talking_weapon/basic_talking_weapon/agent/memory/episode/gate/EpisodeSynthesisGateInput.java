package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Episode gate 的最小正式输入。
 * <p>
 * gate 必须同时看到：
 * - region 本身的边界与聚合信息
 * - region 内的 fixed segments 基础信息
 * - 同时间窗内的 STM slice
 */
public record EpisodeSynthesisGateInput(
        EpisodeRegion region,
        List<FixedActionSegment> regionSegments,
        STMPool.TimeSlice stmSlice,
        long stmSliceStartTimestamp,
        long stmSliceExclusiveTimestamp) {

    public EpisodeSynthesisGateInput {
        region = Objects.requireNonNull(region, "region");
        regionSegments = regionSegments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(regionSegments));
        stmSlice = Objects.requireNonNull(stmSlice, "stmSlice");
        stmSliceStartTimestamp = Math.max(0L, stmSliceStartTimestamp);
        if (stmSliceExclusiveTimestamp < stmSliceStartTimestamp) {
            throw new IllegalArgumentException("stmSliceExclusiveTimestamp must be >= stmSliceStartTimestamp");
        }
    }
}
