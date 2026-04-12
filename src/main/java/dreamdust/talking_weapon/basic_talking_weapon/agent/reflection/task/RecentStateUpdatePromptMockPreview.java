package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionMaterialMerger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service.DefaultReflectionPromptFormatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成一份 recent state update reflection prompt 预览。
 */
public final class RecentStateUpdatePromptMockPreview {

    private static final String DEFAULT_SCENARIO_KEY = "baseline";
    private static final String HOSTILE_MURDERER_SCENARIO_KEY = "hostile_murderer";

    private RecentStateUpdatePromptMockPreview() {
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length >= 1 && args[0] != null && !args[0].isBlank()
                ? Path.of(args[0])
                : Path.of("testdata", "recent_state_update_prompt_mock_preview");
        String scenarioKey = args.length >= 2 ? args[1] : DEFAULT_SCENARIO_KEY;
        PreviewOutput output = generatePreviewOutput(scenarioKey);
        writePreviewFiles(outputDir, output);

        System.out.println("[RecentStateUpdatePromptMockPreview] generated files under " + outputDir.toAbsolutePath());
    }

    public static PreviewOutput generatePreviewOutput(String scenarioKey) {
        EpisodeSynthesisPromptMockPreview.MockScenarioData scenario =
                EpisodeSynthesisPromptMockPreview.loadScenarioData(scenarioKey);
        ReflectionRawMaterial rawMaterial = EpisodeSynthesisPromptMockPreview.buildRawMaterialForScenario(scenarioKey);
        ReflectionMaterial material = new DefaultReflectionMaterialMerger().merge(rawMaterial);
        ReflectionPromptDraft promptDraft = new DefaultReflectionPromptFormatter().format(material);
        ReflectionTaskInput taskInput = new ReflectionTaskInput(
                material,
                scenario.stateSnapshot(),
                scenario.relevantPersonaEntries());
        AgentRecentStateUpdateReflectionTask task = new AgentRecentStateUpdateReflectionTask(
                EpisodeSynthesisPromptMockPreview.buildRecentStateUpdateSignalForScenario(scenario.key()),
                96_000L,
                4L,
                12_000L);

        return new PreviewOutput(
                scenario.key(),
                scenario.description(),
                task.buildSystemPrompt(taskInput, promptDraft),
                task.buildUserPrompt(),
                promptDraft.draftText(),
                promptDraft.segmentReferenceMap(),
                buildSummary(material, scenario));
    }

    public static List<PreviewOutput> generateAllPreviewOutputs() {
        List<PreviewOutput> outputs = new ArrayList<>();
        outputs.add(generatePreviewOutput(DEFAULT_SCENARIO_KEY));
        outputs.add(generatePreviewOutput(HOSTILE_MURDERER_SCENARIO_KEY));
        return List.copyOf(outputs);
    }

    private static String buildSummary(
            ReflectionMaterial material,
            EpisodeSynthesisPromptMockPreview.MockScenarioData scenario) {
        return """
                recent_state_update prompt mock preview

                scenario: %s
                description: %s
                regionId: %s
                segmentRange: %s -> %s
                timeRange: %s -> %s
                stmSlice: %s -> %s
                segmentCount: %s

                generated files:
                - system_prompt.txt
                - user_prompt.txt
                - prompt_draft.txt
                - segment_reference_map.txt
                """.formatted(
                scenario.key(),
                scenario.description(),
                material.regionId(),
                material.startSegmentId(),
                material.endSegmentId(),
                material.startTimestamp(),
                material.endTimestamp(),
                material.stmSliceStartTimestamp(),
                material.stmSliceExclusiveTimestamp(),
                material.segments().size()).trim();
    }

    private static void writePreviewFiles(Path outputDir, PreviewOutput output) throws IOException {
        Files.createDirectories(outputDir);
        write(outputDir.resolve("system_prompt.txt"), output.systemPrompt());
        write(outputDir.resolve("user_prompt.txt"), output.userPrompt());
        write(outputDir.resolve("prompt_draft.txt"), output.promptDraft());
        write(outputDir.resolve("segment_reference_map.txt"), output.segmentReferenceMap());
        write(outputDir.resolve("preview_summary.txt"), output.previewSummary());
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    public record PreviewOutput(
            String scenarioKey,
            String scenarioDescription,
            String systemPrompt,
            String userPrompt,
            String promptDraft,
            String segmentReferenceMap,
            String previewSummary) {

        public PreviewOutput {
            scenarioKey = scenarioKey == null ? "" : scenarioKey;
            scenarioDescription = scenarioDescription == null ? "" : scenarioDescription;
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            userPrompt = userPrompt == null ? "" : userPrompt;
            promptDraft = promptDraft == null ? "" : promptDraft;
            segmentReferenceMap = segmentReferenceMap == null ? "" : segmentReferenceMap;
            previewSummary = previewSummary == null ? "" : previewSummary;
        }
    }
}
