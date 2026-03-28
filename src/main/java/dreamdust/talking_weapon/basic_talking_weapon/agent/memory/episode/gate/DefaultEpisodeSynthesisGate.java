package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRuntimeSettings;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 当前第一版 episode synthesis gate。
 * <p>
 * 规则先保持扁平：
 * <ul>
 * <li>只要 region 时间窗内存在任意 STM 记录，则直接放行</li>
 * <li>如果 STM slice 为空，则按可调 reject 概率判定</li>
 * </ul>
 */
public final class DefaultEpisodeSynthesisGate implements EpisodeSynthesisGate {

    @Override
    public EpisodeSynthesisGateResult evaluate(EpisodeSynthesisGateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }

        STMPool.TimeSlice stmSlice = input.stmSlice();
        if (stmSlice != null && !stmSlice.isEmpty()) {
            return EpisodeSynthesisGateResult.pass("stm_present");
        }

        double rejectProbability = EpisodeRuntimeSettings.getEmptyStmRejectProbability();
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < rejectProbability) {
            return EpisodeSynthesisGateResult.reject(String.format(
                    "empty_stm_reject(roll=%.3f<threshold=%.3f)",
                    roll,
                    rejectProbability));
        }
        return EpisodeSynthesisGateResult.pass(String.format(
                "empty_stm_pass(roll=%.3f>=threshold=%.3f)",
                roll,
                rejectProbability));
    }
}
