package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.List;

/**
 * source material window 的统一提取结果。
 * <p>
 * 与 cleanup plan 配对使用：
 * - snapshot 负责按正式 window 提取 fixed segments + local STM
 * - cleanup 负责按同一正式 window 执行 discard / truncate
 */
public record MemorySourceMaterialSnapshot(
        MemorySourceMaterialWindow window,
        boolean stale,
        List<FixedActionSegment> segments,
        STMPool.TimeSlice stmSlice) {

    public MemorySourceMaterialSnapshot {
        if (window == null) {
            throw new IllegalArgumentException("window must not be null");
        }
        segments = segments == null ? List.of() : List.copyOf(segments);
        stmSlice = stmSlice == null ? STMPool.TimeSlice.empty() : stmSlice;
    }

    public static MemorySourceMaterialSnapshot stale(MemorySourceMaterialWindow requestedWindow) {
        return new MemorySourceMaterialSnapshot(
                requestedWindow,
                true,
                List.of(),
                STMPool.TimeSlice.empty());
    }
}
