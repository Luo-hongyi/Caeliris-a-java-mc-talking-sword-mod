package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterialRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModule;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModuleKey;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionModuleResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module.ReflectionValidationResult;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionMaterialExporter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionMaterialMerger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionPersonaLoader;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionPromptFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.ReflectionStateSnapshotLoader;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.StringJoiner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 新 reflection 主线的抽象 task 骨架。
 * <p>
 * 它只负责固定共用流程：
 * material exporter -> merger -> state/persona load -> prompt draft。
 * 真正的 instruction / LLM / commit / 后续联动留给 concrete task。
 */
public abstract class AbstractReflectionTask extends AgentTask {

    private static final int DEFAULT_BACKGROUND_PRIORITY = 40;

    private final ReflectionTaskType taskType;
    private final List<ReflectionModule> modules;
    private final EnumMap<ReflectionModuleKey, ReflectionModule> modulesByKey;
    private final ReflectionMaterialExporter materialExporter;
    private final ReflectionMaterialMerger materialMerger;
    private final ReflectionStateSnapshotLoader stateSnapshotLoader;
    private final ReflectionPersonaLoader personaLoader;
    private final ReflectionPromptFormatter promptFormatter;

    protected AbstractReflectionTask(
            AgentContext context,
            ReflectionTaskType taskType,
            List<? extends ReflectionModule> modules,
            ReflectionMaterialExporter materialExporter,
            ReflectionMaterialMerger materialMerger,
            ReflectionStateSnapshotLoader stateSnapshotLoader,
            ReflectionPersonaLoader personaLoader,
            ReflectionPromptFormatter promptFormatter) {
        super(DEFAULT_BACKGROUND_PRIORITY, context);
        this.taskType = Objects.requireNonNull(taskType, "taskType");
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("modules must not be empty");
        }
        this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
        this.modulesByKey = new EnumMap<>(ReflectionModuleKey.class);
        for (ReflectionModule module : this.modules) {
            if (module == null) {
                continue;
            }
            ReflectionModule previous = this.modulesByKey.put(module.moduleKey(), module);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate reflection module: " + module.moduleKey());
            }
        }
        this.materialExporter = Objects.requireNonNull(materialExporter, "materialExporter");
        this.materialMerger = Objects.requireNonNull(materialMerger, "materialMerger");
        this.stateSnapshotLoader = Objects.requireNonNull(stateSnapshotLoader, "stateSnapshotLoader");
        this.personaLoader = Objects.requireNonNull(personaLoader, "personaLoader");
        this.promptFormatter = Objects.requireNonNull(promptFormatter, "promptFormatter");
    }

    @Override
    public final String getTaskType() {
        return taskType.displayName();
    }

    public final ReflectionTaskType reflectionTaskType() {
        return taskType;
    }

    protected final List<ReflectionModule> modules() {
        return modules;
    }

    @Override
    public final void execute() {
        if (isCancelled()) {
            return;
        }

        AgentRuntime runtime = AgentRuntime.getInstance();
        ReflectionMaterialRequest materialRequest = buildMaterialRequest();
        ReflectionRawMaterial rawMaterial = Objects.requireNonNull(
                materialExporter.export(materialRequest, runtime.getMemory()),
                "materialExporter.export returned null");

        if (isCancelled()) {
            return;
        }

        ReflectionMaterial material = Objects.requireNonNull(
                materialMerger.merge(rawMaterial),
                "materialMerger.merge returned null");
        ReflectionStateSnapshot stateSnapshot = Objects.requireNonNull(
                stateSnapshotLoader.load(
                        buildStateRequest(),
                        runtime.getMemory(),
                        runtime.getState(),
                        runtime.getPersona(),
                        buildHistoricalStateSnapshot()),
                "stateSnapshotLoader.load returned null");
        RelevantPersonaEntries personaEntries = Objects.requireNonNull(
                personaLoader.load(buildPersonaRequest(), material, runtime.getPersona()),
                "personaLoader.load returned null");
        ReflectionTaskInput input = new ReflectionTaskInput(material, stateSnapshot, personaEntries);
        ReflectionPromptDraft promptDraft = Objects.requireNonNull(
                promptFormatter.format(material),
                "promptFormatter.format returned null");

        executePreparedTask(input, promptDraft);
    }

    protected final String buildModuleInstructionPrompt(ReflectionTaskInput input) {
        StringJoiner joiner = new StringJoiner("\n\n");
        for (ReflectionModule module : modules) {
            String instruction = module.buildInstructionPrompt(input);
            if (instruction == null || instruction.isBlank()) {
                continue;
            }
            joiner.add("[" + module.moduleKey().wireName() + "]\n" + instruction.trim());
        }
        return joiner.toString();
    }

    protected final Map<ReflectionModuleKey, ReflectionModuleResult> parseAndValidateModuleOutputs(
            JsonObject rootObject,
            ReflectionTaskInput input) {
        if (rootObject == null) {
            throw new IllegalArgumentException("rootObject must not be null");
        }
        JsonObject moduleObjectRoot = requireObject(rootObject, "modules");

        for (Map.Entry<String, JsonElement> entry : moduleObjectRoot.entrySet()) {
            if (entry == null) {
                continue;
            }
            String wireName = entry.getKey();
            if (findModuleByWireName(wireName) == null) {
                throw new IllegalArgumentException("Unexpected reflection module payload: " + wireName);
            }
        }

        Map<ReflectionModuleKey, ReflectionModuleResult> parsed = new LinkedHashMap<>();
        for (ReflectionModule module : modules) {
            JsonObject modulePayload = requireObject(moduleObjectRoot, module.moduleKey().wireName());
            ReflectionModuleResult result = module.parseResult(modulePayload);
            ReflectionValidationResult validation = module.validateResult(result, input);
            if (!validation.valid()) {
                throw new IllegalArgumentException(
                        "Module validation failed for " + module.moduleKey() + ": " + String.join("; ", validation.messages()));
            }
            parsed.put(module.moduleKey(), result);
        }

        Map<ReflectionModuleKey, ReflectionModuleResult> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        crossValidateModuleResults(snapshot, input);
        return snapshot;
    }

    protected void crossValidateModuleResults(
            Map<ReflectionModuleKey, ReflectionModuleResult> parsedResults,
            ReflectionTaskInput input) {
    }

    protected abstract ReflectionMaterialRequest buildMaterialRequest();

    protected abstract ReflectionStateRequest buildStateRequest();

    protected abstract ReflectionPersonaRequest buildPersonaRequest();

    protected ReflectionHistoricalStateSnapshot buildHistoricalStateSnapshot() {
        return ReflectionHistoricalStateSnapshot.empty();
    }

    protected abstract void executePreparedTask(
            ReflectionTaskInput input,
            ReflectionPromptDraft promptDraft);

    private ReflectionModule findModuleByWireName(String wireName) {
        for (ReflectionModule module : modules) {
            if (module.moduleKey().wireName().equals(wireName)) {
                return module;
            }
        }
        return null;
    }

    private JsonObject requireObject(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("Missing required object field: " + fieldName);
        }
        return object.getAsJsonObject(fieldName);
    }
}
