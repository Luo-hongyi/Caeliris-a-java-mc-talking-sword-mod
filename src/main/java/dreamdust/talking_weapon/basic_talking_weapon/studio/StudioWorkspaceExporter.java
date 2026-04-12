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
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 导出 Talking Weapon Studio 所需的整包工作区目录。
 * <p>
 * 目标：让 Studio 只需要接收一个文件夹，而不是逐个手动选择 JSON。
 */
public final class StudioWorkspaceExporter {

    private StudioWorkspaceExporter() {
    }

    public static ExportResult exportWorkspace() {
        AgentStateStorage.ensureWorkspaceFileExists(
                AgentRuntime.getInstance().getState(),
                AgentRuntime.getInstance().getPersona());
        CustomTagRegistry.getInstance();
        LTMStorage.loadGlobalImplantedMemories();
        PersonaStorage.loadRuntimePersonas();
        LocationRegistry.getInstance();
        PromptRegistry.getInstance();
        ImportanceRegistry.getInstance();
        NativeCatalogSnapshotExporter.ExportResult snapshot = NativeCatalogSnapshotExporter.exportSnapshot();
        StudioRuntimeTagViewExporter.ExportResult runtimeTagView = StudioRuntimeTagViewExporter.exportMergedView();
        NativeCatalogIconExporter.ExportResult icons = NativeCatalogIconExporter.exportAllIcons();

        Path workspaceRoot = getWorkspaceRoot();
        try {
            Files.createDirectories(workspaceRoot);

            List<String> exportedFiles = new ArrayList<>();
            copyFile(snapshot.outputPath, workspaceRoot.resolve(snapshot.outputPath.getFileName()), exportedFiles);
            copyFile(runtimeTagView.outputPath, workspaceRoot.resolve(runtimeTagView.outputPath.getFileName()), exportedFiles);
            copyDirectory(icons.iconRoot, workspaceRoot.resolve(icons.iconRoot.getFileName()), exportedFiles);

            copyFile(TagDescriptionDictionary.getConfigPath(),
                    workspaceRoot.resolve(TagDescriptionDictionary.getConfigPath().getFileName()), exportedFiles);
            copyFile(TagRelationDictionary.getConfigPath(),
                    workspaceRoot.resolve(TagRelationDictionary.getConfigPath().getFileName()), exportedFiles);
            copyFile(LTMStorage.getGlobalConfigPath(),
                    workspaceRoot.resolve(LTMStorage.getGlobalConfigPath().getFileName()), exportedFiles);
            copyFile(AgentStateStorage.getConfigPath(),
                    workspaceRoot.resolve(AgentStateStorage.getConfigPath().getFileName()), exportedFiles);
            copyFile(CustomTagRegistry.getConfigPath(),
                    workspaceRoot.resolve(CustomTagRegistry.getConfigPath().getFileName()), exportedFiles);
            copyFile(PersonaStorage.getConfigPath(),
                    workspaceRoot.resolve(PersonaStorage.getConfigPath().getFileName()), exportedFiles);
            copyFile(LocationRegistry.getConfigPath(),
                    workspaceRoot.resolve(LocationRegistry.getConfigPath().getFileName()), exportedFiles);
            copyFile(ImportanceRegistry.getConfigPath(),
                    workspaceRoot.resolve(ImportanceRegistry.getConfigPath().getFileName()), exportedFiles);
            copyFile(PromptRegistry.getConfigPath(),
                    workspaceRoot.resolve(PromptRegistry.getConfigPath().getFileName()), exportedFiles);
            copyFile(StudioRuntimeRecentInteractionsFile.getPath(),
                    workspaceRoot.resolve(StudioRuntimeRecentInteractionsFile.getPath().getFileName()), exportedFiles);
            StudioRuntimeSynthesizedMemoriesFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());
            copyFile(StudioRuntimeSynthesizedMemoriesFile.getPath(),
                    workspaceRoot.resolve(StudioRuntimeSynthesizedMemoriesFile.getPath().getFileName()), exportedFiles);
            StudioRuntimeActionTimelineFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());
            copyFile(StudioRuntimeActionTimelineFile.getPath(),
                    workspaceRoot.resolve(StudioRuntimeActionTimelineFile.getPath().getFileName()), exportedFiles);
            StudioRuntimeEpisodeTimelineFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());
            copyFile(StudioRuntimeEpisodeTimelineFile.getPath(),
                    workspaceRoot.resolve(StudioRuntimeEpisodeTimelineFile.getPath().getFileName()), exportedFiles);
            copyFile(StudioRuntimeRecallTraceFile.getPath(),
                    workspaceRoot.resolve(StudioRuntimeRecallTraceFile.getPath().getFileName()), exportedFiles);
            StudioRuntimeReflectionPromptPreviewFile.writeLatest();
            exportedFiles.add(StudioRuntimeReflectionPromptPreviewFile.FILENAME);
            StudioActionTimelineTuningFile.ensureWorkspaceFileExists();
            exportedFiles.add(StudioActionTimelineTuningFile.FILENAME);
            StudioMemoryRecallTuningFile.ensureWorkspaceFileExists();
            exportedFiles.add(StudioMemoryRecallTuningFile.FILENAME);
            StudioEpisodeTuningFile.ensureWorkspaceFileExists();
            exportedFiles.add(StudioEpisodeTuningFile.FILENAME);

            writeManifest(StudioWorkspaceManifestFile.getPath(), snapshot, runtimeTagView, icons, exportedFiles);
            return new ExportResult(workspaceRoot, exportedFiles.size(), snapshot.entryCount,
                    runtimeTagView.tagCount, icons.renderedCount, icons.placeholderCount);
        } catch (IOException e) {
            throw new RuntimeException("Failed to export Studio workspace directory", e);
        }
    }

    public static Path getWorkspaceRoot() {
        return StudioWorkspacePaths.getWorkspaceRoot();
    }

    private static void copyFile(Path source, Path target, List<String> exportedFiles) throws IOException {
        if (source == null || !Files.exists(source)) {
            return;
        }
        if (source.normalize().equals(target.normalize())) {
            exportedFiles.add(workspaceRelative(target));
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        exportedFiles.add(workspaceRelative(target));
    }

    private static void copyDirectory(Path sourceRoot, Path targetRoot, List<String> exportedFiles) throws IOException {
        if (sourceRoot == null || !Files.exists(sourceRoot)) {
            return;
        }
        clearDirectory(targetRoot);
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path source : walk.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    exportedFiles.add(workspaceRelative(target));
                }
            }
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            if (!path.equals(directory)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to clear directory: " + path, e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static void writeManifest(Path manifestPath,
            NativeCatalogSnapshotExporter.ExportResult snapshot,
            StudioRuntimeTagViewExporter.ExportResult runtimeTagView,
            NativeCatalogIconExporter.ExportResult icons,
            List<String> exportedFiles) throws IOException {
        StudioWorkspaceManifestFile manifest = new StudioWorkspaceManifestFile();
        manifest.schemaVersion = StudioWorkspaceManifestFile.SCHEMA_VERSION;
        manifest.generatedAt = OffsetDateTime.now().toString();
        manifest.updatedBy = StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT;
        manifest.snapshotEntries = snapshot.entryCount;
        manifest.runtimeTagCount = runtimeTagView.tagCount;
        manifest.runtimeIssues = runtimeTagView.issueCount;
        manifest.renderedIcons = icons.renderedCount;
        manifest.placeholderIcons = icons.placeholderCount;
        manifest.files = exportedFiles;
        manifest.versions = buildVersions();
        StudioWorkspaceManifestFile.write(manifestPath, manifest);
    }

    private static Map<String, String> buildVersions() {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>(StudioWorkspaceManifestFile.createEmptyVersions());
        versions.put("snapshot", lastModifiedToken(getWorkspaceRoot().resolve("native_catalog.snapshot.json")));
        versions.put("runtimeTagView", lastModifiedToken(getWorkspaceRoot().resolve("studio_runtime_tag_view.json")));
        versions.put("runtimeInteractions", lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeRecentInteractionsFile.FILENAME)));
        versions.put("runtimeSynthesizedMemories",
                lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeSynthesizedMemoriesFile.FILENAME)));
        versions.put("runtimeActionTimeline", lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeActionTimelineFile.FILENAME)));
        versions.put("runtimeActionSegmentStore", lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeActionSegmentStoreFile.FILENAME)));
        versions.put("runtimeActionDiscardLog", lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeActionDiscardLogFile.FILENAME)));
        versions.put("runtimeEpisodeTimeline", lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeEpisodeTimelineFile.FILENAME)));
        versions.put("runtimeRecallTrace", lastModifiedToken(getWorkspaceRoot().resolve(StudioRuntimeRecallTraceFile.FILENAME)));
        versions.put("runtimeReflectionPromptPreview", lastModifiedToken(getWorkspaceRoot().resolve(
                StudioRuntimeReflectionPromptPreviewFile.FILENAME)));
        versions.put("actionTimelineTuning", lastModifiedToken(getWorkspaceRoot().resolve(StudioActionTimelineTuningFile.FILENAME)));
        versions.put("memoryRecallTuning", lastModifiedToken(getWorkspaceRoot().resolve(StudioMemoryRecallTuningFile.FILENAME)));
        versions.put("episodeTuning", lastModifiedToken(getWorkspaceRoot().resolve(StudioEpisodeTuningFile.FILENAME)));
        versions.put("importance", lastModifiedToken(getWorkspaceRoot().resolve("importance.json")));
        versions.put("prompts", lastModifiedToken(getWorkspaceRoot().resolve("prompts.json")));
        versions.put("customTags", lastModifiedToken(getWorkspaceRoot().resolve("custom_tags.json")));
        versions.put("descriptions", lastModifiedToken(getWorkspaceRoot().resolve("tag_descriptions.json")));
        versions.put("relations", lastModifiedToken(getWorkspaceRoot().resolve("tag_relations.json")));
        versions.put("personas", lastModifiedToken(getWorkspaceRoot().resolve("personas.json")));
        versions.put("memories", lastModifiedToken(getWorkspaceRoot().resolve("implanted_memories.json")));
        versions.put("agentState", lastModifiedToken(getWorkspaceRoot().resolve("agent_state.json")));
        versions.put("locations", lastModifiedToken(getWorkspaceRoot().resolve("location_registry.json")));
        versions.put("iconsDirectory", directoryLastModifiedToken(getWorkspaceRoot().resolve("native_catalog_icons")));
        return versions;
    }

    private static String lastModifiedToken(Path path) {
        if (path == null || !Files.exists(path)) {
            return "missing";
        }
        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
            return Long.toString(lastModifiedTime.toMillis());
        } catch (IOException e) {
            return "error";
        }
    }

    private static String directoryLastModifiedToken(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return "missing";
        }
        long latest = 0L;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.toList()) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                latest = Math.max(latest, Files.getLastModifiedTime(path).toMillis());
            }
        } catch (IOException e) {
            return "error";
        }
        return Long.toString(latest);
    }

    private static String workspaceRelative(Path absolutePath) {
        return getWorkspaceRoot().relativize(absolutePath).toString().replace('\\', '/');
    }

    public static final class ExportResult {
        public final Path workspaceRoot;
        public final int fileCount;
        public final int snapshotEntryCount;
        public final int runtimeTagCount;
        public final int renderedIcons;
        public final int placeholderIcons;

        public ExportResult(Path workspaceRoot, int fileCount, int snapshotEntryCount,
                int runtimeTagCount, int renderedIcons, int placeholderIcons) {
            this.workspaceRoot = workspaceRoot;
            this.fileCount = fileCount;
            this.snapshotEntryCount = snapshotEntryCount;
            this.runtimeTagCount = runtimeTagCount;
            this.renderedIcons = renderedIcons;
            this.placeholderIcons = placeholderIcons;
        }
    }
}
