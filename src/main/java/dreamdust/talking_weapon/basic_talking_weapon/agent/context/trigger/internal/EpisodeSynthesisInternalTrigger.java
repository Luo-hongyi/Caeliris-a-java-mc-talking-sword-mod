package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.internal;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.AgentRecentStateUpdateTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.AgentRecentStateUpdateReflectionTask;
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

        boolean submittedAny = false;

        List<AgentRecentStateUpdateTriggerSignal> recentStateSignals =
                memory.getEpisodeTimelineStore().collectRecentStateUpdateTriggerSignals();
        for (AgentRecentStateUpdateTriggerSignal signal : recentStateSignals) {
            if (signal == null) {
                continue;
            }
            boolean accepted = runtime.submitBackground(new AgentRecentStateUpdateReflectionTask(
                    signal,
                    currentGameTime,
                    currentDayIndex,
                    currentTimeOfDayTick));
            if (!accepted) {
                memory.getEpisodeTimelineStore().rollbackRecentStateUpdateToReady(signal.regionId());
                continue;
            }
            submittedAny = true;
        }

        List<EpisodeSynthesisTriggerSignal> synthesisSignals =
                memory.getEpisodeTimelineStore().collectEpisodeSynthesisTriggerSignals();
        for (EpisodeSynthesisTriggerSignal signal : synthesisSignals) {
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
