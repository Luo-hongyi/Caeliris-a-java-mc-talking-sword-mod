package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;

import java.util.Optional;

/**
 * 手动触发的 episode synthesis 调试任务。
 * <p>
 * 当前只用于测试最近一个已闭合 region 的 prompt draft 生成是否会产生卡顿。
 */
public final class DebugEpisodeSynthesisTask extends AgentTask {

    public static final int DEFAULT_PRIORITY = 45;
    public static final String SOURCE_NAME = "debug_episode_synthesis";

    public DebugEpisodeSynthesisTask() {
        super(DEFAULT_PRIORITY, buildContext());
    }

    @Override
    public void execute() {
        if (isCancelled()) {
            return;
        }

        AgentMemory memory = AgentRuntime.getInstance().getMemory();
        Optional<EpisodeSynthesisTriggerSignal> signal = memory.getEpisodeTimelineStore()
                .peekLatestClosedRegionDebugSignal();
        if (signal.isEmpty() || isCancelled()) {
            return;
        }

        Optional<EpisodeSynthesisMaterial> material = memory.getEpisodeTimelineStore().buildSynthesisMaterial(
                signal.get(),
                memory.getSTMPool().snapshotBefore(signal.get().stmSliceExclusiveTimestamp()));
        if (material.isEmpty() || isCancelled()) {
            AgentLogger.logEpisodeSynthesisDebug(signal.get(), "(buildSynthesisMaterial returned empty)");
            return;
        }

        AgentLogger.logEpisodeSynthesisDebug(signal.get(), material.get().buildPromptDraft());
    }

    @Override
    public String getTaskType() {
        return "DebugEpisodeSynthesis";
    }

    private static AgentContext buildContext() {
        return AgentContext.builder()
                .timestamp(System.currentTimeMillis())
                .triggerSource(SOURCE_NAME)
                .build();
    }
}
