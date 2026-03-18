package dreamdust.talking_weapon.basic_talking_weapon.studio;

import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.CustomTagRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagRelationDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
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

    public static PublishResult publishWorkspace() {
        Path workspaceRoot = StudioWorkspaceExporter.getWorkspaceRoot();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("Studio workspace directory does not exist: " + workspaceRoot);
        }

        Path workspaceCustomTags = requireWorkspaceFile(workspaceRoot, "custom_tags.json");
        Path workspaceDescriptions = requireWorkspaceFile(workspaceRoot, "tag_descriptions.json");
        Path workspaceRelations = requireWorkspaceFile(workspaceRoot, "tag_relations.json");
        Path workspacePersonas = requireWorkspaceFile(workspaceRoot, "personas.json");
        Path workspaceMemories = requireWorkspaceFile(workspaceRoot, "implanted_memories.json");

        try {
            List<CustomTagRegistry.CustomTagEntry> customTags = CustomTagRegistry.readEntries(workspaceCustomTags);
            Set<String> customTagSet = CustomTagRegistry.toTagSet(customTags);
            Predicate<String> customTagLookup = customTagSet::contains;

            int descriptionCount = TagDescriptionDictionary.readDefinitions(workspaceDescriptions).size();
            int relationCount = TagRelationDictionary.readEdges(workspaceRelations, customTagLookup).size();
            int personaCount = PersonaStorage.readEntries(workspacePersonas, customTagLookup).size();
            int memoryCount = LTMStorage.validateConfig(workspaceMemories, customTagLookup);

            List<String> publishedFiles = new ArrayList<>();
            publishedFiles.add(workspaceCustomTags.getFileName().toString());
            publishedFiles.add(workspaceDescriptions.getFileName().toString());
            publishedFiles.add(workspaceRelations.getFileName().toString());
            publishedFiles.add(workspacePersonas.getFileName().toString());
            publishedFiles.add(workspaceMemories.getFileName().toString());

            acknowledgeWorkspaceManifest();

            CustomTagRegistry.reload();
            TagDescriptionDictionary.reload();
            TagRelationDictionary.reload();
            AgentRuntime.getInstance().getPersona().reloadStudioPersonaEntries();
            AgentRuntime.getInstance().getMemory().reloadImplantedMemories();

            return new PublishResult(workspaceRoot, publishedFiles, customTags.size(), descriptionCount, relationCount,
                    personaCount, memoryCount);
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

        public PublishResult(Path workspaceRoot, List<String> publishedFiles, int customTagCount,
                int descriptionCount, int relationCount, int personaCount, int memoryCount) {
            this.workspaceRoot = workspaceRoot;
            this.publishedFiles = List.copyOf(publishedFiles);
            this.customTagCount = customTagCount;
            this.descriptionCount = descriptionCount;
            this.relationCount = relationCount;
            this.personaCount = personaCount;
            this.memoryCount = memoryCount;
        }
    }
}
