package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.internal.EpisodeSynthesisTrigger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeEpisodeTimelineFile;

import java.util.Optional;

/**
 * Episode 驱动的 LTM 合成任务骨架。
 * <p>
 * 当前阶段先只负责占住正式接口位置：
 * <ul>
 * <li>未来从 episode signal 读取 closed region</li>
 * <li>结合 STM 时间切片组织输入</li>
 * <li>调用 LLM 生成 LTM</li>
 * <li>成功后再回写 commit / discard</li>
 * </ul>
 */
public final class EpisodeSynthesisTask extends AgentTask {

    public static final int DEFAULT_PRIORITY = 40;

    private final EpisodeSynthesisTriggerSignal signal;

    public EpisodeSynthesisTask(EpisodeSynthesisTriggerSignal signal) {
        super(DEFAULT_PRIORITY, buildInternalContext(signal));
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        this.signal = signal;
    }

    @Override
    public void execute() {
        if (isCancelled()) {
            return;
        }

        AgentMemory memory = AgentRuntime.getInstance().getMemory();
        Optional<EpisodeSynthesisMaterial> material = memory.getEpisodeTimelineStore().buildSynthesisMaterial(
                signal,
                memory.getSTMPool().snapshotBefore(signal.stmSliceExclusiveTimestamp()));
        if (material.isEmpty() || isCancelled()) {
            return;
        }

        StudioRuntimeEpisodeTimelineFile.recordSynthesisMaterial(material.get());
        AgentLogger.logDebug("[Episode Synthesis Prompt Draft]\n" + material.get().buildPromptDraft());

        // TODO: 基于 material 组织 LTM synthesis 输入，并在成功后提交 discard / commit。
    }

    @Override
    public String getTaskType() {
        return "EpisodeSynthesis";
    }

    public EpisodeSynthesisTriggerSignal getSignal() {
        return signal;
    }

    private static AgentContext buildInternalContext(EpisodeSynthesisTriggerSignal signal) {
        long now = System.currentTimeMillis();
        AgentContext.Builder builder = AgentContext.builder()
                .timestamp(now)
                .triggerSource(EpisodeSynthesisTrigger.SOURCE_NAME);

        if (signal != null) {
            builder.extra("episode_region_start_segment_index", Integer.toString(signal.regionStartSegmentIndex()));
            builder.extra("episode_region_end_segment_index", Integer.toString(signal.regionEndSegmentIndex()));
            builder.extra("episode_stm_slice_exclusive_timestamp", Long.toString(signal.stmSliceExclusiveTimestamp()));
        }
        return builder.build();
    }
}
