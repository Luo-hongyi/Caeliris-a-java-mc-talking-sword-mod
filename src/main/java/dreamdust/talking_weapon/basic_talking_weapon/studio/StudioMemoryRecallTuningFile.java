package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * Recall 公式参数在 Studio workspace 中的真源文件。
 * <p>
 * 该文件承载 `TalkingWeaponConfig` 中与 memory recall 公式相关的参数，
 * 游戏运行时与 Studio 直接共用同一份 authored JSON；运行时加载后会同步回 Forge config / toml，
 * 让现有依赖 `TalkingWeaponConfig` 的公式实现继续工作。
 */
public final class StudioMemoryRecallTuningFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "memory_recall_tuning.json";
    public static final String VERSION_KEY = "memoryRecallTuning";
    public static final int SCHEMA_VERSION = 1;

    private StudioMemoryRecallTuningFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized MemoryRecallTuningFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return createCurrentModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            MemoryRecallTuningFileModel file = GSON.fromJson(reader, MemoryRecallTuningFileModel.class);
            if (file == null) {
                file = createCurrentModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRecallTuning] 读取失败：" + e.getMessage());
            return createCurrentModel();
        }
    }

    public static synchronized void writeCurrent() {
        write(createCurrentModel());
    }

    public static synchronized void applyToRuntime(MemoryRecallTuningFileModel file) {
        applyToRuntime(file, true);
    }

    public static synchronized boolean applyWorkspaceValuesToRuntime(boolean persistConfig) {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return false;
        }
        applyToRuntime(readOrEmpty(), persistConfig);
        return true;
    }

    public static synchronized void ensureWorkspaceFileExists() {
        if (!Files.isRegularFile(getPath())) {
            writeCurrent();
        }
    }

    public static synchronized void applyToRuntime(MemoryRecallTuningFileModel file, boolean persistConfig) {
        MemoryRecallTuningFileModel safeFile = file != null ? file : createCurrentModel();
        safeFile.normalize();

        TalkingWeaponConfig.LTM_RECALL_MAIN_CANDIDATES_PER_CUE.set(safeFile.mainCandidatesPerCue);
        TalkingWeaponConfig.LTM_RECALL_SANDBOX_CUES_PER_MEMORY.set(safeFile.sandboxCuesPerMemory);
        TalkingWeaponConfig.LTM_RECALL_CONSCIOUS_MEMORY_CAP.set(safeFile.consciousMemoryCap);
        TalkingWeaponConfig.LTM_RECALL_RECENT_WINDOW_CAP.set(safeFile.recentRecallWindowCap);
        TalkingWeaponConfig.LTM_RECALL_RECENT_WINDOW.set(safeFile.recentRecallWindow);
        TalkingWeaponConfig.LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE.set(safeFile.implantedFallbackEntryScore);
        TalkingWeaponConfig.LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE.set(safeFile.promptFillProbabilityScale);
        TalkingWeaponConfig.LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS.set(safeFile.tagPoolHalfLifeDays);
        TalkingWeaponConfig.LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS.set(safeFile.fermentationHalfLifeDays);
        TalkingWeaponConfig.LTM_RECALL_INTERVAL_BONUS_SCALE.set(safeFile.intervalBonusScale);
        TalkingWeaponConfig.LTM_RECALL_INTERVAL_BONUS_STEEPNESS.set(safeFile.intervalBonusSteepness);
        TalkingWeaponConfig.LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE.set(safeFile.synthesizedMemoryHalfLife);
        TalkingWeaponConfig.LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE.set(safeFile.desireProfileBonusScale);
        TalkingWeaponConfig.LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE.set(safeFile.desiredFocusBonusScale);
        if (persistConfig) {
            TalkingWeaponConfig.SPEC.save();
        }
    }

    private static MemoryRecallTuningFileModel createCurrentModel() {
        MemoryRecallTuningFileModel file = new MemoryRecallTuningFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.mainCandidatesPerCue = TalkingWeaponConfig.LTM_RECALL_MAIN_CANDIDATES_PER_CUE.get();
        file.sandboxCuesPerMemory = TalkingWeaponConfig.LTM_RECALL_SANDBOX_CUES_PER_MEMORY.get();
        file.consciousMemoryCap = TalkingWeaponConfig.LTM_RECALL_CONSCIOUS_MEMORY_CAP.get();
        file.recentRecallWindow = TalkingWeaponConfig.LTM_RECALL_RECENT_WINDOW.get();
        file.recentRecallWindowCap = TalkingWeaponConfig.getRecentRecallWindowCapValue();
        file.implantedFallbackEntryScore = TalkingWeaponConfig.getImplantedFallbackEntryScoreValue();
        file.promptFillProbabilityScale = TalkingWeaponConfig.getPromptFillProbabilityScaleValue();
        file.tagPoolHalfLifeDays = TalkingWeaponConfig.LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS.get();
        file.fermentationHalfLifeDays = TalkingWeaponConfig.LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS.get();
        file.intervalBonusScale = TalkingWeaponConfig.LTM_RECALL_INTERVAL_BONUS_SCALE.get();
        file.intervalBonusSteepness = TalkingWeaponConfig.LTM_RECALL_INTERVAL_BONUS_STEEPNESS.get();
        file.synthesizedMemoryHalfLife = TalkingWeaponConfig.LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE.get();
        file.desireProfileBonusScale = TalkingWeaponConfig.LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE.get();
        file.desiredFocusBonusScale = TalkingWeaponConfig.LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE.get();
        file.normalize();
        return file;
    }

    private static void write(MemoryRecallTuningFileModel file) {
        Path path = getPath();
        MemoryRecallTuningFileModel safeFile = file != null ? file : createCurrentModel();
        safeFile.normalize();
        safeFile.generatedAt = OffsetDateTime.now().toString();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(safeFile, writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY, StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRecallTuning] 写入失败：" + e.getMessage());
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class MemoryRecallTuningFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public int mainCandidatesPerCue = 1;
        public int sandboxCuesPerMemory = 2;
        public int consciousMemoryCap = 3;
        public int recentRecallWindow = 6;
        public int recentRecallWindowCap = 20;
        public double implantedFallbackEntryScore = 1.25;
        public double promptFillProbabilityScale = 1.0;
        public double tagPoolHalfLifeDays = 0.40;
        public double fermentationHalfLifeDays = 2.50;
        public double intervalBonusScale = 0.95;
        public double intervalBonusSteepness = 4.0;
        public double synthesizedMemoryHalfLife = 63.0;
        public double desireProfileBonusScale = 0.25;
        public double desiredFocusBonusScale = 0.18;

        public void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            mainCandidatesPerCue = clampInt(mainCandidatesPerCue, 1, 6);
            sandboxCuesPerMemory = clampInt(sandboxCuesPerMemory, 0, 4);
            consciousMemoryCap = clampInt(consciousMemoryCap, 0, 8);
            recentRecallWindowCap = clampInt(recentRecallWindowCap, 1, 256);
            recentRecallWindow = clampInt(recentRecallWindow, 1, recentRecallWindowCap);
            implantedFallbackEntryScore = clampDouble(implantedFallbackEntryScore, 0.0, 3.0);
            promptFillProbabilityScale = clampDouble(promptFillProbabilityScale, 0.0, 3.0);
            tagPoolHalfLifeDays = clampDouble(tagPoolHalfLifeDays, 0.05, 30.0);
            fermentationHalfLifeDays = clampDouble(fermentationHalfLifeDays, 0.10, 120.0);
            intervalBonusScale = clampDouble(intervalBonusScale, 0.0, 10.0);
            intervalBonusSteepness = clampDouble(intervalBonusSteepness, 0.1, 20.0);
            synthesizedMemoryHalfLife = clampDouble(synthesizedMemoryHalfLife, 1.0, 240.0);
            desireProfileBonusScale = clampDouble(desireProfileBonusScale, 0.0, 2.0);
            desiredFocusBonusScale = clampDouble(desiredFocusBonusScale, 0.0, 2.0);
        }
    }
}
