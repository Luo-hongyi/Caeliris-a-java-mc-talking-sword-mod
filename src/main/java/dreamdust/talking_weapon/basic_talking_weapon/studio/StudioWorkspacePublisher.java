package dreamdust.talking_weapon.basic_talking_weapon.studio;

import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.CustomTagRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagRelationDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentStateStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 校验并热重载 Studio 工作区内的 authored schema。
 * <p>
 * 现在 authored JSON 直接以 {@code studio_workspace/} 为真源；
 * 因此这里不再做文件搬运，只负责：
 * <ul>
 * <li>校验 workspace 内的 authored JSON 结构</li>
 * <li>通知运行时重新加载这些文件</li>
 * <li>回写 manifest，告诉 Studio 变更已被 Minecraft 端接纳</li>
 * </ul>
 */
public final class StudioWorkspacePublisher {

    private StudioWorkspacePublisher() {
    }

    public static synchronized PublishResult publishWorkspace() {
        Path workspaceRoot = StudioWorkspaceExporter.getWorkspaceRoot();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("Studio workspace directory does not exist: " + workspaceRoot);
        }

        LocationRegistry.getInstance();
        ImportanceRegistry.getInstance();
        PromptRegistry.getInstance();
        Path workspaceCustomTags = requireWorkspaceFile(workspaceRoot, "custom_tags.json");
        Path workspaceDescriptions = requireWorkspaceFile(workspaceRoot, "tag_descriptions.json");
        Path workspaceRelations = requireWorkspaceFile(workspaceRoot, "tag_relations.json");
        Path workspacePersonas = requireWorkspaceFile(workspaceRoot, "personas.json");
        Path workspaceMemories = requireWorkspaceFile(workspaceRoot, "implanted_memories.json");
        Path workspaceAgentState = requireWorkspaceFile(workspaceRoot, "agent_state.json");
        Path workspaceLocations = requireWorkspaceFile(workspaceRoot, "location_registry.json");
        Path workspaceImportance = requireWorkspaceFile(workspaceRoot, "importance.json");
        Path workspacePrompts = requireWorkspaceFile(workspaceRoot, "prompts.json");
        Path workspaceActionTimelineTuning = workspaceRoot.resolve(StudioActionTimelineTuningFile.FILENAME);
        Path workspaceRecallTuning = workspaceRoot.resolve(StudioMemoryRecallTuningFile.FILENAME);
        Path workspaceEpisodeTuning = workspaceRoot.resolve(StudioEpisodeTuningFile.FILENAME);

        try {
            List<CustomTagRegistry.CustomTagEntry> customTags = CustomTagRegistry.readEntries(workspaceCustomTags);
            Set<String> customTagSet = CustomTagRegistry.toTagSet(customTags);
            Predicate<String> customTagLookup = customTagSet::contains;

            int descriptionCount = TagDescriptionDictionary.readDefinitions(workspaceDescriptions).size();
            int relationCount = TagRelationDictionary.readEdges(workspaceRelations, customTagLookup).size();
            int personaCount = PersonaStorage.readEntries(workspacePersonas, customTagLookup).size();
            int memoryCount = LTMStorage.validateConfig(workspaceMemories, customTagLookup);
            AgentStateStorage.validateConfig(workspaceAgentState);
            int locationCount = LocationRegistry.readEntries(workspaceLocations).size();
            ImportanceRegistry.readEntries(workspaceImportance);
            PromptRegistry.readEntries(workspacePrompts);
            if (Files.isRegularFile(workspaceActionTimelineTuning)) {
                StudioActionTimelineTuningFile.applyToRuntime(StudioActionTimelineTuningFile.readOrEmpty());
            } else {
                StudioActionTimelineTuningFile.ensureWorkspaceFileExists();
            }
            if (Files.isRegularFile(workspaceRecallTuning)) {
                StudioMemoryRecallTuningFile.applyToRuntime(StudioMemoryRecallTuningFile.readOrEmpty());
            } else {
                StudioMemoryRecallTuningFile.ensureWorkspaceFileExists();
            }
            if (Files.isRegularFile(workspaceEpisodeTuning)) {
                StudioEpisodeTuningFile.applyToRuntime(StudioEpisodeTuningFile.readOrEmpty());
            } else {
                StudioEpisodeTuningFile.ensureWorkspaceFileExists();
            }

            List<String> publishedFiles = new ArrayList<>();
            publishedFiles.add(workspaceCustomTags.getFileName().toString());
            publishedFiles.add(workspaceDescriptions.getFileName().toString());
            publishedFiles.add(workspaceRelations.getFileName().toString());
            publishedFiles.add(workspacePersonas.getFileName().toString());
            publishedFiles.add(workspaceMemories.getFileName().toString());
            publishedFiles.add(workspaceAgentState.getFileName().toString());
            publishedFiles.add(workspaceLocations.getFileName().toString());
            publishedFiles.add(workspaceImportance.getFileName().toString());
            publishedFiles.add(workspacePrompts.getFileName().toString());
            if (Files.isRegularFile(workspaceActionTimelineTuning)) {
                publishedFiles.add(workspaceActionTimelineTuning.getFileName().toString());
            }
            if (Files.isRegularFile(workspaceRecallTuning)) {
                publishedFiles.add(workspaceRecallTuning.getFileName().toString());
            }
            if (Files.isRegularFile(workspaceEpisodeTuning)) {
                publishedFiles.add(workspaceEpisodeTuning.getFileName().toString());
            }

            acknowledgeWorkspaceManifest();

            CustomTagRegistry.reload();
            TagDescriptionDictionary.reload();
            TagRelationDictionary.reload();
            AgentRuntime.getInstance().getPersona().reloadStudioPersonaEntries();
            AgentRuntime.getInstance().getMemory().reloadImplantedMemories();
            AgentStateStorage.loadIntoRuntime(AgentRuntime.getInstance().getState(), AgentRuntime.getInstance().getPersona());
            LocationRegistry.reload();
            ImportanceRegistry.reload();
            PromptRegistry.reload();
            StudioRuntimeActionTimelineFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());
            StudioRuntimeEpisodeTimelineFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());

            return new PublishResult(workspaceRoot, publishedFiles, customTags.size(), descriptionCount, relationCount,
                    personaCount, memoryCount, locationCount);
        } catch (IOException e) {
            throw new RuntimeException("Failed to publish Studio workspace", e);
        }
    }

    private static void acknowledgeWorkspaceManifest() throws IOException {
        Path manifestPath = StudioWorkspaceManifestFile.getPath();
        StudioWorkspaceManifestFile manifest = StudioWorkspaceManifestFile.readIfExists(manifestPath);
        if (manifest == null) {
            return;
        }
        manifest.updatedBy = StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT;
        manifest.generatedAt = OffsetDateTime.now().toString();
        StudioWorkspaceManifestFile.write(manifestPath, manifest);
    }

    private static Path requireWorkspaceFile(Path workspaceRoot, String filename) {
        Path file = workspaceRoot.resolve(filename);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Missing Studio workspace file: " + filename);
        }
        return file;
    }

    public static final class PublishResult {
        public final Path workspaceRoot;
        public final List<String> publishedFiles;
        public final int customTagCount;
        public final int descriptionCount;
        public final int relationCount;
        public final int personaCount;
        public final int memoryCount;
        public final int locationCount;

        public PublishResult(Path workspaceRoot, List<String> publishedFiles, int customTagCount,
                int descriptionCount, int relationCount, int personaCount, int memoryCount, int locationCount) {
            this.workspaceRoot = workspaceRoot;
            this.publishedFiles = List.copyOf(publishedFiles);
            this.customTagCount = customTagCount;
            this.descriptionCount = descriptionCount;
            this.relationCount = relationCount;
            this.personaCount = personaCount;
            this.memoryCount = memoryCount;
            this.locationCount = locationCount;
        }
    }
}
