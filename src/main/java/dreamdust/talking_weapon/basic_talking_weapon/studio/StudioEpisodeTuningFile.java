package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeReasonRuntimeConfig;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter.EpisodeSplitReason;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Episode splitter 在 Studio workspace 中的 authored tuning 文件。
 */
public final class StudioEpisodeTuningFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "episode_tuning.json";
    public static final String VERSION_KEY = "episodeTuning";
    public static final int SCHEMA_VERSION = 1;

    private StudioEpisodeTuningFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized EpisodeTuningFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return createCurrentModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            EpisodeTuningFileModel file = GSON.fromJson(reader, EpisodeTuningFileModel.class);
            if (file == null) {
                file = createCurrentModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioEpisodeTuning] 读取失败：" + e.getMessage());
            return createCurrentModel();
        }
    }

    public static synchronized boolean applyWorkspaceValuesToRuntime() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return false;
        }
        applyToRuntime(readOrEmpty());
        return true;
    }

    public static synchronized void ensureWorkspaceFileExists() {
        if (!Files.isRegularFile(getPath())) {
            writeCurrent();
        }
    }

    public static synchronized void writeCurrent() {
        write(createCurrentModel());
    }

    public static synchronized void applyToRuntime(EpisodeTuningFileModel file) {
        EpisodeTuningFileModel safeFile = file != null ? file : createCurrentModel();
        safeFile.normalize();
        EpisodeRuntimeSettings.setFallbackQuietWindowSegments(safeFile.fallbackQuietWindowSegments);

        LinkedHashMap<EpisodeSplitReason, EpisodeReasonRuntimeConfig> configs =
                new LinkedHashMap<>();
        for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
            EpisodeReasonTuningFileModel reasonModel = safeFile.reasons.get(reason.workspaceKey());
            EpisodeReasonRuntimeConfig config = reasonModel == null
                    ? EpisodeRuntimeSettings.defaultConfig(reason)
                    : reasonModel.toRuntimeConfig();
            configs.put(reason, config);
        }
        EpisodeRuntimeSettings.replaceAllConfigs(configs);
    }

    private static EpisodeTuningFileModel createCurrentModel() {
        EpisodeTuningFileModel file = new EpisodeTuningFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.fallbackQuietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        file.reasons = createReasonModels(EpisodeRuntimeSettings.snapshotConfigs());
        file.normalize();
        return file;
    }

    private static LinkedHashMap<String, EpisodeReasonTuningFileModel> createReasonModels(
            Map<EpisodeSplitReason, EpisodeReasonRuntimeConfig> configs) {
        LinkedHashMap<String, EpisodeReasonTuningFileModel> reasonModels = new LinkedHashMap<>();
        for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
            EpisodeReasonRuntimeConfig config = configs == null
                    ? EpisodeRuntimeSettings.defaultConfig(reason)
                    : configs.getOrDefault(reason, EpisodeRuntimeSettings.defaultConfig(reason));
            reasonModels.put(reason.workspaceKey(), EpisodeReasonTuningFileModel.fromRuntimeConfig(config));
        }
        return reasonModels;
    }

    private static void write(EpisodeTuningFileModel file) {
        Path path = getPath();
        EpisodeTuningFileModel safeFile = file != null ? file : createCurrentModel();
        safeFile.normalize();
        safeFile.generatedAt = OffsetDateTime.now().toString();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(safeFile, writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY, StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioEpisodeTuning] 写入失败：" + e.getMessage());
        }
    }

    public static final class EpisodeTuningFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public int fallbackQuietWindowSegments = EpisodeRuntimeSettings.getFallbackQuietWindowSegments();
        public LinkedHashMap<String, EpisodeReasonTuningFileModel> reasons =
                createReasonModels(EpisodeRuntimeSettings.snapshotConfigs());

        public void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            fallbackQuietWindowSegments = Math.max(1, Math.min(64, fallbackQuietWindowSegments));
            Map<String, EpisodeReasonTuningFileModel> sourceReasons = reasons == null ? Map.of() : reasons;
            LinkedHashMap<String, EpisodeReasonTuningFileModel> normalizedReasons = new LinkedHashMap<>();
            for (EpisodeSplitReason reason : EpisodeSplitReason.values()) {
                EpisodeReasonTuningFileModel reasonModel = sourceReasons.get(reason.workspaceKey());
                if (reasonModel == null) {
                    reasonModel = EpisodeReasonTuningFileModel.fromRuntimeConfig(
                            EpisodeRuntimeSettings.defaultConfig(reason));
                }
                reasonModel.normalize();
                normalizedReasons.put(reason.workspaceKey(), reasonModel);
            }
            reasons = normalizedReasons;
        }
    }

    public static final class EpisodeReasonTuningFileModel {
        public boolean highPriority;
        public int confirmDelaySegments;
        public int coverBeforeSegments;
        public int coverAfterSegments;

        public void normalize() {
            EpisodeReasonRuntimeConfig normalized = toRuntimeConfig();
            highPriority = normalized.highPriority();
            confirmDelaySegments = normalized.confirmDelaySegments();
            coverBeforeSegments = normalized.coverBeforeSegments();
            coverAfterSegments = normalized.coverAfterSegments();
        }

        public EpisodeReasonRuntimeConfig toRuntimeConfig() {
            return new EpisodeReasonRuntimeConfig(
                    highPriority,
                    confirmDelaySegments,
                    coverBeforeSegments,
                    coverAfterSegments);
        }

        public static EpisodeReasonTuningFileModel fromRuntimeConfig(EpisodeReasonRuntimeConfig config) {
            EpisodeReasonRuntimeConfig safeConfig = config != null
                    ? config
                    : new EpisodeReasonRuntimeConfig(false, 0, 0, 0);
            EpisodeReasonTuningFileModel model = new EpisodeReasonTuningFileModel();
            model.highPriority = safeConfig.highPriority();
            model.confirmDelaySegments = safeConfig.confirmDelaySegments();
            model.coverBeforeSegments = safeConfig.coverBeforeSegments();
            model.coverAfterSegments = safeConfig.coverAfterSegments();
            model.normalize();
            return model;
        }
    }
}
