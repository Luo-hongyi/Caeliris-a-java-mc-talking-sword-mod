package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMAgentService;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMMessage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.AgentRecentStateUpdateTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterialRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.EmotionUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.EmotionUpdateResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.FavorabilityUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.FavorabilityUpdateResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.PlayerProfileUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.PlayerProfileUpdateResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.RecapUpdateModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.RecapUpdateResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModuleKey;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModuleResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.prompt.ReflectionPromptResources;
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
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentStateStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * episode 封口后立即触发的 recent state update task。
 * <p>
 * 它只读 source material，不做 cleanup；
 * 负责以更低延迟刷新 player profile / recap / favorability / emotion。
 */
public final class AgentRecentStateUpdateReflectionTask extends AbstractReflectionTask {

    public static final String SOURCE_NAME = "agent_recent_state_update_reflection";
    private static final String ROLEPLAY_REASONING_FIELD = "roleplay_reasoning";
    private static final String SYSTEM_PROMPT_HEADER = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_AGENT_RECENT_STATE_UPDATE_SYSTEM_HEADER).trim();
    private static final String USER_PROMPT = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_AGENT_RECENT_STATE_UPDATE_USER_PROMPT).trim();
    private static final String IDENTITY_SECTION_TEMPLATE = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_IDENTITY_SECTION).trim();
    private static final String SHARED_REFLECTION_SYSTEM_HEADER = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_REFLECTION_SYSTEM_HEADER).trim();
    private static final String STATE_SECTION_TEMPLATE = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_STATE_SNAPSHOT_SECTION).trim();
    private static final String RELEVANT_PERSONA_HEADER = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_RELEVANT_PERSONA_HEADER).trim();
    private static final String RELEVANT_PERSONA_ENTRY_TEMPLATE = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_RELEVANT_PERSONA_ENTRY).trim();
    private static final String RELEVANT_PERSONA_NONE = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_RELEVANT_PERSONA_NONE).trim();
    private static final String TASK_CONSTRAINTS = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_TASK_CONSTRAINTS).trim();
    private static final String OUTPUT_RULES = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.TASK_SHARED_OUTPUT_RULES).trim();
    private static final String TASK_LOG_PREFIX = "[TalkingWeapon/Reflection] ";
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Pattern MARKDOWN_JSON = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*}", Pattern.DOTALL);

    private static final List<ReflectionModule> FIXED_MODULES = List.of(
            new PlayerProfileUpdateModule(),
            new RecapUpdateModule(),
            new FavorabilityUpdateModule(),
            new EmotionUpdateModule());

    private final AgentRecentStateUpdateTriggerSignal signal;

    public AgentRecentStateUpdateReflectionTask(AgentRecentStateUpdateTriggerSignal signal) {
        this(
                signal,
                -1L,
                -1L,
                -1L,
                new DefaultReflectionMaterialExporter(),
                new DefaultReflectionMaterialMerger(),
                new DefaultReflectionStateSnapshotLoader(),
                new DefaultReflectionPersonaLoader(),
                new DefaultReflectionPromptFormatter());
    }

    public AgentRecentStateUpdateReflectionTask(
            AgentRecentStateUpdateTriggerSignal signal,
            long currentGameTime,
            long currentDayIndex,
            long currentTimeOfDayTick) {
        this(
                signal,
                currentGameTime,
                currentDayIndex,
                currentTimeOfDayTick,
                new DefaultReflectionMaterialExporter(),
                new DefaultReflectionMaterialMerger(),
                new DefaultReflectionStateSnapshotLoader(),
                new DefaultReflectionPersonaLoader(),
                new DefaultReflectionPromptFormatter());
    }

    public AgentRecentStateUpdateReflectionTask(
            AgentRecentStateUpdateTriggerSignal signal,
            long currentGameTime,
            long currentDayIndex,
            long currentTimeOfDayTick,
            ReflectionMaterialExporter materialExporter,
            ReflectionMaterialMerger materialMerger,
            ReflectionStateSnapshotLoader stateSnapshotLoader,
            ReflectionPersonaLoader personaLoader,
            ReflectionPromptFormatter promptFormatter) {
        super(
                buildContext(signal, currentGameTime, currentDayIndex, currentTimeOfDayTick),
                ReflectionTaskType.AGENT_RECENT_STATE_UPDATE,
                FIXED_MODULES,
                materialExporter,
                materialMerger,
                stateSnapshotLoader,
                personaLoader,
                promptFormatter);
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    @Override
    protected ReflectionMaterialRequest buildMaterialRequest() {
        return ReflectionMaterialRequest.forRecentStateUpdateSignal(signal);
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
    protected ReflectionHistoricalStateSnapshot buildHistoricalStateSnapshot() {
        return new ReflectionHistoricalStateSnapshot(
                signal.segmentClosePrimaryEmotion(),
                signal.segmentCloseEmotionIntensity(),
                signal.segmentCloseFavorability(),
                signal.episodeClosePlayerProfile(),
                signal.episodeCloseRecap());
    }

    @Override
    protected void executePreparedTask(
            ReflectionTaskInput input,
            ReflectionPromptDraft promptDraft) {
        AgentRuntime runtime = AgentRuntime.getInstance();
        String systemPrompt = buildSystemPrompt(input, promptDraft);
        String userPrompt = buildUserPrompt();
        boolean taskOwnedCommitApplied = false;

        try {
            if (isCancelled()) {
                rollbackPendingRegion(runtime);
                return;
            }

            AgentLogger.logPrompts(systemPrompt, userPrompt, SOURCE_NAME, false);
            String rawReply = LLMAgentService.buildClient().generate(List.of(
                    LLMMessage.system(systemPrompt),
                    LLMMessage.user(userPrompt)));
            AgentLogger.logRawResponse(rawReply);

            if (isCancelled()) {
                rollbackPendingRegion(runtime);
                return;
            }

            JsonObject rootObject = parseRootObject(rawReply);
            requireTopLevelNonBlankString(rootObject, ROLEPLAY_REASONING_FIELD);
            Map<ReflectionModuleKey, ReflectionModuleResult> parsedResults =
                    parseAndValidateModuleOutputs(rootObject, input);

            applyModuleResults(parsedResults, runtime);
            taskOwnedCommitApplied = true;
            if (!runtime.getMemory().getEpisodeTimelineStore().markRecentStateUpdateResolved(signal.regionId())) {
                throw new IllegalStateException(
                        "Recent state update finalize failed for regionId=" + signal.regionId());
            }
            AgentStateStorage.requestSaveRuntimeState(runtime.getState(), runtime.getPersona());
        } catch (InterruptedException interrupted) {
            if (!taskOwnedCommitApplied) {
                rollbackPendingRegion(runtime);
            } else {
                logTaskNotice("recent state update interrupted after task-owned commit; region kept pending"
                        + " regionId=" + signal.regionId());
            }
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (!taskOwnedCommitApplied) {
                rollbackPendingRegion(runtime);
            } else {
                logTaskNotice("recent state update failed after task-owned commit; region kept pending"
                        + " regionId=" + signal.regionId()
                        + " cause=" + exception.getClass().getSimpleName());
            }
            throw new IllegalStateException(
                    "Recent state update reflection failed for regionId=" + signal.regionId(),
                    exception);
        }
    }

    String buildSystemPrompt(ReflectionTaskInput input, ReflectionPromptDraft promptDraft) {
        StringJoiner joiner = new StringJoiner("\n\n");
        joiner.add(SYSTEM_PROMPT_HEADER);
        joiner.add(SHARED_REFLECTION_SYSTEM_HEADER);

        String cognitionRule = PromptRegistry.getInstance().getSystemCognitionRule();
        if (cognitionRule != null && !cognitionRule.isBlank()) {
            joiner.add("[System Cognition Rule]\n" + cognitionRule.trim());
        }

        joiner.add(buildIdentitySection(input.state()));
        joiner.add(buildStateSection(input.state()));
        joiner.add(buildRelevantPersonaSection(input.relevantPersonaEntries()));

        if (promptDraft != null && !promptDraft.draftText().isBlank()) {
            joiner.add(promptDraft.draftText().trim());
        }
        if (promptDraft != null && !promptDraft.segmentReferenceMap().isBlank()) {
            joiner.add("[Segment Reference Map]\n" + promptDraft.segmentReferenceMap().trim());
        }

        joiner.add(TASK_CONSTRAINTS);

        String moduleInstruction = buildModuleInstructionPrompt(input);
        if (!moduleInstruction.isBlank()) {
            joiner.add("[Module Instructions]\n" + moduleInstruction);
        }

        joiner.add("[Output Contract]\n" + buildRootOutputContractPrompt());
        joiner.add(OUTPUT_RULES);
        return joiner.toString();
    }

    String buildUserPrompt() {
        return USER_PROMPT;
    }

    private String buildIdentitySection(ReflectionStateSnapshot state) {
        return IDENTITY_SECTION_TEMPLATE.formatted(
                safeText(state.identity()),
                safeText(state.roleBackground()),
                safeText(state.rolePersonality()));
    }

    private String buildStateSection(ReflectionStateSnapshot state) {
        return STATE_SECTION_TEMPLATE.formatted(
                safeText(state.primaryEmotion()),
                safeNumber(state.emotionIntensity()),
                safeNumber(state.familiarity()),
                safeText(state.playerAddress()),
                safeText(state.currentDesireDescription()),
                safeNumber(state.favorability()),
                safeText(state.playerProfile()),
                safeText(state.recap()));
    }

    private String buildRelevantPersonaSection(RelevantPersonaEntries relevantPersonaEntries) {
        List<PersonaEntry> entries = relevantPersonaEntries == null ? List.of() : relevantPersonaEntries.entries();
        if (entries == null || entries.isEmpty()) {
            return RELEVANT_PERSONA_HEADER + "\n" + RELEVANT_PERSONA_NONE;
        }

        StringBuilder builder = new StringBuilder(RELEVANT_PERSONA_HEADER).append('\n');
        for (PersonaEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            builder.append(RELEVANT_PERSONA_ENTRY_TEMPLATE.formatted(
                    safeText(entry.getDescription()))).append('\n');
        }
        return builder.toString().trim();
    }

    private String buildRootOutputContractPrompt() {
        JsonObject moduleProperties = new JsonObject();
        JsonArray moduleRequired = new JsonArray();
        for (ReflectionModule module : modules()) {
            if (module == null) {
                continue;
            }
            moduleProperties.add(module.moduleKey().wireName(), module.buildOutputContract());
            moduleRequired.add(module.moduleKey().wireName());
        }

        JsonObject modulesContract = new JsonObject();
        modulesContract.addProperty("type", "object");
        modulesContract.add("required", moduleRequired);
        modulesContract.add("properties", moduleProperties);

        JsonObject roleplayReasoningContract = new JsonObject();
        roleplayReasoningContract.addProperty("type", "string");
        roleplayReasoningContract.addProperty(
                "description",
                "简短说明这次你如何把握剑的立场、语气、偏好与主体区分；该字段仅用于调试。");

        JsonObject rootProperties = new JsonObject();
        rootProperties.add(ROLEPLAY_REASONING_FIELD, roleplayReasoningContract);
        rootProperties.add("modules", modulesContract);

        JsonArray rootRequired = new JsonArray();
        rootRequired.add(ROLEPLAY_REASONING_FIELD);
        rootRequired.add("modules");

        JsonObject rootContract = new JsonObject();
        rootContract.addProperty("type", "object");
        rootContract.add("required", rootRequired);
        rootContract.add("properties", rootProperties);
        return PRETTY_GSON.toJson(rootContract);
    }

    private String requireTopLevelNonBlankString(JsonObject rootObject, String fieldName) {
        if (rootObject == null || fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("rootObject and fieldName must not be null");
        }
        if (!rootObject.has(fieldName) || rootObject.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("Missing required top-level string field: " + fieldName);
        }
        String value = rootObject.get(fieldName).getAsString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Top-level string field must not be blank: " + fieldName);
        }
        return value;
    }

    private JsonObject parseRootObject(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            throw new IllegalArgumentException("Reflection reply is empty");
        }

        JsonObject direct = tryParseJsonObject(rawReply.trim());
        if (direct != null) {
            return direct;
        }

        Matcher markdownMatcher = MARKDOWN_JSON.matcher(rawReply);
        if (markdownMatcher.find()) {
            JsonObject markdown = tryParseJsonObject(markdownMatcher.group(1).trim());
            if (markdown != null) {
                return markdown;
            }
        }

        Matcher jsonMatcher = JSON_BLOCK.matcher(rawReply);
        if (jsonMatcher.find()) {
            JsonObject block = tryParseJsonObject(jsonMatcher.group().trim());
            if (block != null) {
                return block;
            }
        }

        throw new IllegalArgumentException("Failed to parse reflection JSON root object from raw reply");
    }

    private JsonObject tryParseJsonObject(String candidate) {
        try {
            JsonElement root = JsonParser.parseString(candidate);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyModuleResults(
            Map<ReflectionModuleKey, ReflectionModuleResult> parsedResults,
            AgentRuntime runtime) {
        PlayerProfileUpdateResult playerProfile = requireTypedResult(
                parsedResults,
                ReflectionModuleKey.PLAYER_PROFILE_UPDATE,
                PlayerProfileUpdateResult.class);
        RecapUpdateResult recap = requireTypedResult(
                parsedResults,
                ReflectionModuleKey.RECAP_UPDATE,
                RecapUpdateResult.class);
        FavorabilityUpdateResult favorability = requireTypedResult(
                parsedResults,
                ReflectionModuleKey.FAVORABILITY_UPDATE,
                FavorabilityUpdateResult.class);
        EmotionUpdateResult emotion = requireTypedResult(
                parsedResults,
                ReflectionModuleKey.EMOTION_UPDATE,
                EmotionUpdateResult.class);

        AgentPersona persona = runtime.getPersona();
        persona.setPlayerProfile(playerProfile.profileText());
        persona.adjustFavorability(favorability.delta());

        AgentState state = runtime.getState();
        state.setPrimaryEmotionByName(emotion.emotion());
        state.setEmotionIntensity(emotion.intensity());
        state.setRecap(recap.recapText());
    }

    private void rollbackPendingRegion(AgentRuntime runtime) {
        boolean rolledBack = runtime.getMemory().getEpisodeTimelineStore()
                .rollbackRecentStateUpdateToReady(signal.regionId());
        if (!rolledBack) {
            logTaskNotice("failed to rollback recent state update region to ready"
                    + " regionId=" + signal.regionId());
        }
    }

    private <T extends ReflectionModuleResult> T requireTypedResult(
            Map<ReflectionModuleKey, ReflectionModuleResult> parsedResults,
            ReflectionModuleKey key,
            Class<T> expectedType) {
        ReflectionModuleResult result = parsedResults.get(key);
        if (!expectedType.isInstance(result)) {
            throw new IllegalStateException("Missing or invalid reflection module result: " + key);
        }
        return expectedType.cast(result);
    }

    private void logTaskNotice(String message) {
        AgentLogger.logDebug(message);
        System.out.println(TASK_LOG_PREFIX + message);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "(none)" : value.trim();
    }

    private String safeNumber(Number value) {
        return value == null ? "(none)" : value.toString();
    }

    private static AgentContext buildContext(
            AgentRecentStateUpdateTriggerSignal signal,
            long currentGameTime,
            long currentDayIndex,
            long currentTimeOfDayTick) {
        Objects.requireNonNull(signal, "signal");
        return AgentContext.builder()
                .timestamp(System.currentTimeMillis())
                .gameTime(currentGameTime)
                .dayIndex(currentDayIndex)
                .timeOfDayTick(currentTimeOfDayTick)
                .triggerSource(SOURCE_NAME)
                .extra("episode_region_id", Long.toString(signal.regionId()))
                .extra("episode_region_start_segment_id", Long.toString(signal.regionStartSegmentId()))
                .extra("episode_region_end_segment_id", Long.toString(signal.regionEndSegmentId()))
                .extra("episode_left_boundary_left_segment_id", Long.toString(signal.leftBoundaryLeftSegmentId()))
                .extra("episode_left_boundary_right_segment_id", Long.toString(signal.leftBoundaryRightSegmentId()))
                .extra("episode_right_boundary_left_segment_id", Long.toString(signal.rightBoundaryLeftSegmentId()))
                .extra("episode_right_boundary_right_segment_id", Long.toString(signal.rightBoundaryRightSegmentId()))
                .extra("episode_region_start_timestamp", Long.toString(signal.regionStartTimestamp()))
                .extra("episode_region_end_timestamp", Long.toString(signal.regionEndTimestamp()))
                .extra("episode_stm_slice_start_timestamp", Long.toString(signal.stmSliceStartTimestamp()))
                .extra("episode_stm_slice_exclusive_timestamp", Long.toString(signal.stmSliceExclusiveTimestamp()))
                .build();
    }
}
