package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceManifestFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * agent_state.json 的统一读写层。
 * <p>
 * 该文件负责承接：
 * <ul>
 * <li>AgentState 的运行时字段</li>
 * <li>AgentPersona 中不属于 personas.json 的动态字段（favorability / playerProfile）</li>
 * <li>StateScreen 中暴露的注意力权重配置</li>
 * </ul>
 * 目标是让游戏内 UI 与 Studio 共享同一份 authored 真源。
 */
public final class AgentStateStorage {

    private static final String LOG_PREFIX = "[TalkingWeapon/AgentStateStorage] ";
    private static final String CONFIG_FILENAME = "agent_state.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final long DEBOUNCE_MILLIS = 250L;
    private static final Object SAVE_LOCK = new Object();
    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "TalkingWeapon-AgentStateSave");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static ScheduledFuture<?> pendingSave;
    private static AgentStateFile pendingSnapshot;

    private AgentStateStorage() {
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    public static void ensureWorkspaceFileExists(AgentState state, AgentPersona persona) {
        ensureConfigExists(getConfigPath(), state, persona);
    }

    public static void loadIntoRuntime(AgentState state, AgentPersona persona) {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath, state, persona);
        try {
            apply(readFile(configPath), state, persona);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，保留当前运行时默认值：" + e.getMessage());
        }
    }

    public static AgentStateFile readFile(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return parseFile(root);
        }
    }

    public static void validateConfig(Path configPath) throws IOException {
        readFile(configPath);
    }

    public static void requestSaveRuntimeState(AgentState state, AgentPersona persona) {
        AgentStateFile snapshot = capture(state, persona);
        synchronized (SAVE_LOCK) {
            pendingSnapshot = snapshot;
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = SAVE_EXECUTOR.schedule(() -> flushPendingSnapshot(), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public static void flushSaveRuntimeState(AgentState state, AgentPersona persona) {
        writeSnapshot(capture(state, persona));
    }

    private static void flushPendingSnapshot() {
        AgentStateFile snapshot;
        synchronized (SAVE_LOCK) {
            snapshot = pendingSnapshot;
            pendingSnapshot = null;
            pendingSave = null;
        }
        if (snapshot != null) {
            writeSnapshot(snapshot);
        }
    }

    private static void writeSnapshot(AgentStateFile snapshot) {
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated("agentState",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }

    private static void ensureConfigExists(Path configPath, AgentState state, AgentPersona persona) {
        if (Files.exists(configPath)) {
            return;
        }
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(capture(state, persona), writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated("agentState",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
            System.out.println(LOG_PREFIX + "已创建默认配置：" + configPath);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建默认配置失败：" + e.getMessage());
        }
    }

    private static AgentStateFile parseFile(JsonElement root) {
        if (root == null || root.isJsonNull()) {
            return new AgentStateFile();
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("agent_state.json root must be an object");
        }
        validateRecallSchema(root.getAsJsonObject());
        AgentStateFile file = GSON.fromJson(root, AgentStateFile.class);
        return file != null ? normalized(file) : new AgentStateFile();
    }

    private static void validateRecallSchema(JsonObject root) {
        JsonElement stateElement = root.get("state");
        if (stateElement == null || !stateElement.isJsonObject()) {
            return;
        }

        JsonObject state = stateElement.getAsJsonObject();
        if (state.has("association_mode") || state.has("custom_recall")) {
            throw new IllegalArgumentException(
                    "agent_state.json still uses legacy recall fields; expected state.recall_preset and state.recall_tuning");
        }
    }

    private static AgentStateFile normalized(AgentStateFile raw) {
        AgentStateFile file = raw != null ? raw : new AgentStateFile();
        if (file.schemaVersion < 7) {
            file.schemaVersion = 7;
        }
        if (file.state == null) {
            file.state = new AgentRuntimeStateSection();
        }
        if (file.persona_state == null) {
            file.persona_state = new AgentPersonaStateSection();
        }
        if (file.attention_weights == null) {
            file.attention_weights = new AttentionWeightsSection();
        }
        if (file.state.recall_tuning == null) {
            file.state.recall_tuning = RecallTuningSection.fromConfig(AgentState.RecallTuning.defaults());
        } else {
            file.state.recall_tuning = file.state.recall_tuning.normalized();
        }
        if (file.state.implanted_memory_safety_net == null) {
            file.state.implanted_memory_safety_net = false;
        }
        if (file.state.full_raw_action_log == null) {
            file.state.full_raw_action_log = false;
        }
        return file;
    }

    private static AgentStateFile capture(AgentState state, AgentPersona persona) {
        AgentStateFile file = new AgentStateFile();
        file.state.primary_emotion = state.getPrimaryEmotion().name();
        file.state.emotion_intensity = state.getEmotionIntensity();
        file.state.familiarity = state.getFamiliarity();
        file.state.player_address = state.getPlayerAddress();
        file.state.concentration = state.getConcentration();
        file.state.wandering_mind = state.getWanderingMind();
        file.state.nostalgia = state.getNostalgia();
        file.state.recall_preset = state.getRecallPreset().name();
        file.state.recall_tuning = RecallTuningSection.fromConfig(state.getRecallTuning());
        file.state.implanted_memory_safety_net = state.isImplantedMemorySafetyNetEnabled();
        file.state.full_raw_action_log = state.isFullRawActionLogEnabled();
        file.state.current_desire_tag = state.getCurrentDesireTag() != null ? state.getCurrentDesireTag().name() : null;
        file.state.desire_description = state.getDesireDescription();
        file.state.recap = state.getRecap();

        file.persona_state.favorability = persona.getFavorability();
        file.persona_state.player_profile = persona.getPlayerProfile();

        file.attention_weights.combat = TalkingWeaponConfig.WEIGHT_COMBAT.get();
        file.attention_weights.explore = TalkingWeaponConfig.WEIGHT_EXPLORE.get();
        file.attention_weights.labor = TalkingWeaponConfig.WEIGHT_LABOR.get();
        file.attention_weights.creation = TalkingWeaponConfig.WEIGHT_CREATION.get();
        file.attention_weights.creature = TalkingWeaponConfig.WEIGHT_CREATURE.get();
        file.attention_weights.social = TalkingWeaponConfig.WEIGHT_SOCIAL.get();
        file.attention_weights.landscape = TalkingWeaponConfig.WEIGHT_LANDSCAPE.get();
        file.attention_weights.hazard = TalkingWeaponConfig.WEIGHT_HAZARD.get();
        file.attention_weights.status = TalkingWeaponConfig.WEIGHT_STATUS.get();
        file.attention_weights.consumable = TalkingWeaponConfig.WEIGHT_CONSUMABLE.get();
        file.attention_weights.block = TalkingWeaponConfig.WEIGHT_BLOCK.get();
        file.attention_weights.tool = TalkingWeaponConfig.WEIGHT_TOOL.get();
        file.attention_weights.common = TalkingWeaponConfig.WEIGHT_COMMON.get();
        file.attention_weights.crosshair_bonus_enabled = TalkingWeaponConfig.LOOKING_AT_ATTENTION_BONUS_ENABLED.get();
        return file;
    }

    private static void apply(AgentStateFile rawFile, AgentState state, AgentPersona persona) {
        AgentStateFile file = normalized(rawFile);

        state.setPrimaryEmotionByName(file.state.primary_emotion);
        state.setEmotionIntensity(file.state.emotion_intensity);
        state.setFamiliarity(file.state.familiarity);
        state.setPlayerAddress(file.state.player_address);
        state.setConcentration(file.state.concentration);
        state.setWanderingMind(file.state.wandering_mind);
        state.setNostalgia(file.state.nostalgia);
        state.setRecallPresetByName(file.state.recall_preset);
        state.setRecallTuning(file.state.recall_tuning.toConfig());
        state.setImplantedMemorySafetyNetEnabled(Boolean.TRUE.equals(file.state.implanted_memory_safety_net));
        state.setFullRawActionLogEnabled(Boolean.TRUE.equals(file.state.full_raw_action_log));
        state.setCurrentDesireTag(parseFocusTag(file.state.current_desire_tag));
        state.setDesireDescription(file.state.desire_description);
        state.setRecap(file.state.recap);

        persona.setFavorability(file.persona_state.favorability);
        persona.setPlayerProfile(file.persona_state.player_profile);

        applyWeight(TalkingWeaponConfig.WEIGHT_COMBAT, file.attention_weights.combat);
        applyWeight(TalkingWeaponConfig.WEIGHT_EXPLORE, file.attention_weights.explore);
        applyWeight(TalkingWeaponConfig.WEIGHT_LABOR, file.attention_weights.labor);
        applyWeight(TalkingWeaponConfig.WEIGHT_CREATION, file.attention_weights.creation);
        applyWeight(TalkingWeaponConfig.WEIGHT_CREATURE, file.attention_weights.creature);
        applyWeight(TalkingWeaponConfig.WEIGHT_SOCIAL, file.attention_weights.social);
        applyWeight(TalkingWeaponConfig.WEIGHT_LANDSCAPE, file.attention_weights.landscape);
        applyWeight(TalkingWeaponConfig.WEIGHT_HAZARD, file.attention_weights.hazard);
        applyWeight(TalkingWeaponConfig.WEIGHT_STATUS, file.attention_weights.status);
        applyWeight(TalkingWeaponConfig.WEIGHT_CONSUMABLE, file.attention_weights.consumable);
        applyWeight(TalkingWeaponConfig.WEIGHT_BLOCK, file.attention_weights.block);
        applyWeight(TalkingWeaponConfig.WEIGHT_TOOL, file.attention_weights.tool);
        applyWeight(TalkingWeaponConfig.WEIGHT_COMMON, file.attention_weights.common);
        TalkingWeaponConfig.LOOKING_AT_ATTENTION_BONUS_ENABLED.set(file.attention_weights.crosshair_bonus_enabled);
        TalkingWeaponConfig.SPEC.save();
    }

    private static void applyWeight(net.minecraftforge.common.ForgeConfigSpec.DoubleValue configValue, double value) {
        configValue.set(Math.max(0.0d, Math.min(10.0d, value)));
    }

    private static FocusTag parseFocusTag(String raw) {
        return FocusTag.parseOrNull(raw);
    }

    public static final class AgentStateFile {
        public int schemaVersion = 7;
        public AgentRuntimeStateSection state = new AgentRuntimeStateSection();
        public AgentPersonaStateSection persona_state = new AgentPersonaStateSection();
        public AttentionWeightsSection attention_weights = new AttentionWeightsSection();
    }

    public static final class AgentRuntimeStateSection {
        public String primary_emotion = AgentState.PrimaryEmotion.NEUTRAL.name();
        public int emotion_intensity = 0;
        public int familiarity = 0;
        public String player_address = "主人";
        public float concentration = 0.5f;
        public float wandering_mind = 0.1f;
        public float nostalgia = 0.5f;
        public String recall_preset = AgentState.RecallPreset.HYBRID.name();
        public RecallTuningSection recall_tuning = RecallTuningSection.fromConfig(AgentState.RecallTuning.defaults());
        public Boolean implanted_memory_safety_net = null;
        public Boolean full_raw_action_log = false;
        public String current_desire_tag = null;
        public String desire_description = "";
        public String recap = "";
    }

    /**
     * CUSTOM 召回预设的 authored 参数。
     * <p>
     * 三个值分别控制：
     * <ul>
     * <li>主召回阶段：每个 cue 最多能提起多少条候选记忆</li>
     * <li>sandbox 联想阶段：每条表层记忆最多回灌多少个联想 cue</li>
     * <li>最终阶段：最多允许多少条记忆进入表层意识</li>
     * <li>retained fermentation 层：最多保留多少条发酵中的候选记忆</li>
     * </ul>
     */
    public static final class RecallTuningSection {
        public int main_candidates_per_cue = TalkingWeaponConfig.LTM_RECALL_MAIN_CANDIDATES_PER_CUE.get();
        public int sandbox_cues_per_memory = TalkingWeaponConfig.LTM_RECALL_SANDBOX_CUES_PER_MEMORY.get();
        public int conscious_memory_cap = TalkingWeaponConfig.LTM_RECALL_CONSCIOUS_MEMORY_CAP.get();
        public int retention_memory_cap = TalkingWeaponConfig.LTM_RECALL_RETENTION_MEMORY_CAP.get();

        public static RecallTuningSection fromConfig(AgentState.RecallTuning config) {
            AgentState.RecallTuning normalized = config != null
                    ? config
                    : AgentState.RecallTuning.defaults();
            RecallTuningSection section = new RecallTuningSection();
            section.main_candidates_per_cue = normalized.getMainCandidatesPerCue();
            section.sandbox_cues_per_memory = normalized.getSandboxCuesPerMemory();
            section.conscious_memory_cap = normalized.getConsciousMemoryCap();
            section.retention_memory_cap = normalized.getRetentionMemoryCap();
            return section;
        }

        public RecallTuningSection normalized() {
            return fromConfig(toConfig());
        }

        public AgentState.RecallTuning toConfig() {
            return new AgentState.RecallTuning(
                    main_candidates_per_cue,
                    sandbox_cues_per_memory,
                    conscious_memory_cap,
                    retention_memory_cap);
        }
    }

    public static final class AgentPersonaStateSection {
        public int favorability = 0;
        public String player_profile = "";
    }

    public static final class AttentionWeightsSection {
        public double combat = 1.0d;
        public double explore = 1.0d;
        public double labor = 1.0d;
        public double creation = 1.0d;
        public double creature = 1.0d;
        public double social = 1.0d;
        public double landscape = 1.0d;
        public double hazard = 1.0d;
        public double status = 1.0d;
        public double consumable = 1.0d;
        public double block = 1.0d;
        public double tool = 1.0d;
        public double common = 1.0d;
        public boolean crosshair_bonus_enabled = true;
    }
}
