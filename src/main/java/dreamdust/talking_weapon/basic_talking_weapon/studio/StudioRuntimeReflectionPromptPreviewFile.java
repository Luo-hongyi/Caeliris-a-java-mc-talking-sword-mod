package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.EpisodeSynthesisPromptMockPreview;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 reflection prompt mock preview 写入 Studio workspace。
 * <p>
 * 当前先导出固定的对比场景，方便 Studio 直接检查 prompt 组装结果，
 * 不必额外运行 Gradle task。
 */
public final class StudioRuntimeReflectionPromptPreviewFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_reflection_prompt_preview.json";
    public static final String VERSION_KEY = "runtimeReflectionPromptPreview";
    public static final int SCHEMA_VERSION = 1;

    private StudioRuntimeReflectionPromptPreviewFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeReflectionPromptPreviewFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return new RuntimeReflectionPromptPreviewFileModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeReflectionPromptPreviewFileModel file =
                    GSON.fromJson(reader, RuntimeReflectionPromptPreviewFileModel.class);
            if (file == null) {
                file = new RuntimeReflectionPromptPreviewFileModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeReflectionPromptPreview] 读取失败：" + e.getMessage());
            return new RuntimeReflectionPromptPreviewFileModel();
        }
    }

    public static synchronized void writeLatest() {
        RuntimeReflectionPromptPreviewFileModel file = new RuntimeReflectionPromptPreviewFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        for (EpisodeSynthesisPromptMockPreview.PreviewOutput output :
                EpisodeSynthesisPromptMockPreview.generateAllPreviewOutputs()) {
            if (output == null) {
                continue;
            }
            ScenarioPromptPreviewEntry entry = new ScenarioPromptPreviewEntry();
            entry.scenarioKey = output.scenarioKey();
            entry.scenarioDescription = output.scenarioDescription();
            entry.systemPrompt = output.systemPrompt();
            entry.userPrompt = output.userPrompt();
            entry.promptDraft = output.promptDraft();
            entry.segmentReferenceMap = output.segmentReferenceMap();
            entry.previewSummary = output.previewSummary();
            file.scenarios.add(entry);
        }
        write(file);
    }

    private static void write(RuntimeReflectionPromptPreviewFileModel file) {
        Path path = getPath();
        try {
            file.normalize();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated(
                    VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeReflectionPromptPreview] 写入失败：" + e.getMessage());
        }
    }

    public static final class RuntimeReflectionPromptPreviewFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public String reflectionTaskType = "episode_synthesis";
        public List<ScenarioPromptPreviewEntry> scenarios = new ArrayList<>();

        private void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            if (reflectionTaskType == null || reflectionTaskType.isBlank()) {
                reflectionTaskType = "episode_synthesis";
            }
            if (scenarios == null) {
                scenarios = new ArrayList<>();
            }
            for (ScenarioPromptPreviewEntry scenario : scenarios) {
                if (scenario != null) {
                    scenario.normalize();
                }
            }
            scenarios.removeIf(scenario -> scenario == null);
        }
    }

    public static final class ScenarioPromptPreviewEntry {
        public String scenarioKey = "";
        public String scenarioDescription = "";
        public String systemPrompt = "";
        public String userPrompt = "";
        public String promptDraft = "";
        public String segmentReferenceMap = "";
        public String previewSummary = "";

        private void normalize() {
            if (scenarioKey == null) {
                scenarioKey = "";
            }
            if (scenarioDescription == null) {
                scenarioDescription = "";
            }
            if (systemPrompt == null) {
                systemPrompt = "";
            }
            if (userPrompt == null) {
                userPrompt = "";
            }
            if (promptDraft == null) {
                promptDraft = "";
            }
            if (segmentReferenceMap == null) {
                segmentReferenceMap = "";
            }
            if (previewSummary == null) {
                previewSummary = "";
            }
        }
    }
}
