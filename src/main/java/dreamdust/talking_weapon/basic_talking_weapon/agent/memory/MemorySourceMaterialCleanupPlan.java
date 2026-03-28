package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate.EpisodeSynthesisGateInput;

/**
 * owner-side 正式源素材清理计划。
 * <p>
 * 这份 plan 只定义“要清理哪段源素材、为什么清理”，
 * 真正的执行仍由 memory owner 在后续统一收口。
 */
public record MemorySourceMaterialCleanupPlan(
        MemorySourceMaterialCleanupReason reason,
        long regionId,
        MemorySourceMaterialWindow window) {

    public MemorySourceMaterialCleanupPlan {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (regionId <= 0L) {
            throw new IllegalArgumentException("regionId must be > 0");
        }
        if (window == null) {
            throw new IllegalArgumentException("window must not be null");
        }
    }

    public static MemorySourceMaterialCleanupPlan forEpisodeGateReject(EpisodeSynthesisGateInput input) {
        if (input == null || input.region() == null) {
            throw new IllegalArgumentException("input and input.region must not be null");
        }
        return new MemorySourceMaterialCleanupPlan(
                MemorySourceMaterialCleanupReason.EPISODE_GATE_REJECT,
                input.region().regionId(),
                MemorySourceMaterialWindow.forEpisodeGateInput(input));
    }

    public static MemorySourceMaterialCleanupPlan forEpisodeSynthesisConsumed(EpisodeSynthesisTriggerSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        return new MemorySourceMaterialCleanupPlan(
                MemorySourceMaterialCleanupReason.EPISODE_SYNTHESIS_CONSUMED,
                signal.regionId(),
                MemorySourceMaterialWindow.forEpisodeSignal(signal));
    }
}
