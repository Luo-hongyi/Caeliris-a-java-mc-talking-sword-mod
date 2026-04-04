package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.internal;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.EpisodeSynthesisReflectionTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;

import java.util.List;

/**
 * episode synthesis 的内部 trigger。
 * <p>
 * 它只负责读取 owner 已经准备好的 ready signals，
 * 然后按顺序逐个包装成后台 reflection task 提交。
 */
public final class EpisodeSynthesisInternalTrigger {

    public boolean submitReadySignals(
            long currentGameTime,
            long currentDayIndex,
            long currentTimeOfDayTick) {
        AgentRuntime runtime = AgentRuntime.getInstance();
        AgentMemory memory = runtime.getMemory();
        if (memory == null) {
            return false;
        }

        List<EpisodeSynthesisTriggerSignal> signals =
                memory.getEpisodeTimelineStore().collectEpisodeSynthesisTriggerSignals();
        if (signals.isEmpty()) {
            return false;
        }

        boolean submittedAny = false;
        for (EpisodeSynthesisTriggerSignal signal : signals) {
            if (signal == null) {
                continue;
            }
            boolean accepted = runtime.submitBackground(new EpisodeSynthesisReflectionTask(
                    signal,
                    currentGameTime,
                    currentDayIndex,
                    currentTimeOfDayTick));
            if (!accepted) {
                memory.getEpisodeTimelineStore().rollbackEpisodeSynthesisToHolding(signal.regionId());
                continue;
            }
            submittedAny = true;
        }
        return submittedAny;
    }
}
