package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterialRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.CurrentSituationUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.EmotionUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.FavorabilityUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.MemorySynthesisModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.PlayerProfileUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionMaterialExporter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionMaterialMerger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionPersonaLoader;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionPromptFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionStateSnapshotLoader;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionMaterialExporter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionMaterialMerger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionPersonaLoader;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionPromptFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionStateSnapshotLoader;

import java.util.List;
import java.util.Objects;

/**
 * 第一条正式 concrete reflection task。
 * <p>
 * 当前先冻结固定 modules、input 请求与 task 边界，
 * LLM 调用与 commit 逻辑留待下一步接入。
 */
public final class EpisodeSynthesisReflectionTask extends AbstractReflectionTask {

    public static final String SOURCE_NAME = "episode_synthesis_reflection";

    private static final List<ReflectionModule> FIXED_MODULES = List.of(
            new MemorySynthesisModule(),
            new PlayerProfileUpdateModule(),
            new CurrentSituationUpdateModule(),
            new FavorabilityUpdateModule(),
            new EmotionUpdateModule());

    private final EpisodeSynthesisTriggerSignal signal;

    public EpisodeSynthesisReflectionTask(EpisodeSynthesisTriggerSignal signal) {
        this(
                signal,
                new DefaultReflectionMaterialExporter(),
                new DefaultReflectionMaterialMerger(),
                new DefaultReflectionStateSnapshotLoader(),
                new DefaultReflectionPersonaLoader(),
                new DefaultReflectionPromptFormatter());
    }

    public EpisodeSynthesisReflectionTask(
            EpisodeSynthesisTriggerSignal signal,
            ReflectionMaterialExporter materialExporter,
            ReflectionMaterialMerger materialMerger,
            ReflectionStateSnapshotLoader stateSnapshotLoader,
            ReflectionPersonaLoader personaLoader,
            ReflectionPromptFormatter promptFormatter) {
        super(
                buildContext(signal),
                ReflectionTaskType.EPISODE_SYNTHESIS,
                FIXED_MODULES,
                materialExporter,
                materialMerger,
                stateSnapshotLoader,
                personaLoader,
                promptFormatter);
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    public EpisodeSynthesisTriggerSignal getSignal() {
        return signal;
    }

    @Override
    protected ReflectionMaterialRequest buildMaterialRequest() {
        return ReflectionMaterialRequest.forEpisodeSynthesisSignal(signal);
    }

    @Override
    protected ReflectionStateRequest buildStateRequest() {
        return new ReflectionStateRequest(
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true);
    }

    @Override
    protected ReflectionPersonaRequest buildPersonaRequest() {
        return new ReflectionPersonaRequest(true, true);
    }

    @Override
    protected void executePreparedTask(
            ReflectionTaskInput input,
            ReflectionPromptDraft promptDraft) {
        String moduleInstruction = buildModuleInstructionPrompt(input);
        // 第一版只冻结 task 主干，不接 LLM / commit。
        AgentLogger.logDebug(
                "[Reflection Skeleton] episode_synthesis prepared"
                        + "\nsegments=" + input.material().segments().size()
                        + "\nmodules=" + modules().stream().map(module -> module.moduleKey().wireName()).toList()
                        + "\npromptDraftLength=" + promptDraft.draftText().length()
                        + "\nmoduleInstructionLength=" + moduleInstruction.length());
    }

    private static AgentContext buildContext(EpisodeSynthesisTriggerSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return AgentContext.builder()
                .timestamp(System.currentTimeMillis())
                .triggerSource(SOURCE_NAME)
                .extra("episode_region_id", Long.toString(signal.regionId()))
                .extra("episode_region_start_segment_index", Integer.toString(signal.regionStartSegmentIndex()))
                .extra("episode_region_start_segment_id", Long.toString(signal.regionStartSegmentId()))
                .extra("episode_region_end_segment_index", Integer.toString(signal.regionEndSegmentIndex()))
                .extra("episode_region_end_segment_id", Long.toString(signal.regionEndSegmentId()))
                .extra("episode_left_boundary_split_segment_index", Integer.toString(signal.leftBoundarySplitSegmentIndex()))
                .extra("episode_left_boundary_left_segment_id", Long.toString(signal.leftBoundaryLeftSegmentId()))
                .extra("episode_left_boundary_right_segment_id", Long.toString(signal.leftBoundaryRightSegmentId()))
                .extra("episode_right_boundary_split_segment_index", Integer.toString(signal.rightBoundarySplitSegmentIndex()))
                .extra("episode_right_boundary_left_segment_id", Long.toString(signal.rightBoundaryLeftSegmentId()))
                .extra("episode_right_boundary_right_segment_id", Long.toString(signal.rightBoundaryRightSegmentId()))
                .extra("episode_stm_slice_exclusive_timestamp", Long.toString(signal.stmSliceExclusiveTimestamp()))
                .build();
    }
}
