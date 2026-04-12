package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;

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
 * 将 runtime synthesized memories 写入 Studio workspace。
 * <p>
 * 这份文件只承担 Studio 只读调试视图。
 * 真正的持久化由 append-only journal 负责。
 */
public final class StudioRuntimeSynthesizedMemoriesFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_synthesized_memories.json";
    public static final String VERSION_KEY = "runtimeSynthesizedMemories";
    public static final int SCHEMA_VERSION = 1;

    private StudioRuntimeSynthesizedMemoriesFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeSynthesizedMemoriesFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return new RuntimeSynthesizedMemoriesFileModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeSynthesizedMemoriesFileModel file = GSON.fromJson(reader, RuntimeSynthesizedMemoriesFileModel.class);
            if (file == null) {
                file = new RuntimeSynthesizedMemoriesFileModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeSynthesizedMemories] 读取失败：" + e.getMessage());
            return new RuntimeSynthesizedMemoriesFileModel();
        }
    }

    public static synchronized void writeCurrentSnapshot(AgentMemory memory) {
        RuntimeSynthesizedMemoriesFileModel file = new RuntimeSynthesizedMemoriesFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        if (memory != null) {
            for (dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry entry : memory.getLtmRecords()) {
                if (entry == null
                        || entry.getSourceType()
                                != dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry.SourceType.SYNTHESIZED) {
                    continue;
                }
                file.entries.add(RuntimeSynthesizedMemoryEntry.fromEntry(entry));
            }
        }
        write(file);
    }

    private static void write(RuntimeSynthesizedMemoriesFileModel file) {
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
            System.err.println("[TalkingWeapon/StudioRuntimeSynthesizedMemories] 写入失败：" + e.getMessage());
        }
    }

    public static final class RuntimeSynthesizedMemoriesFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public List<RuntimeSynthesizedMemoryEntry> entries = new ArrayList<>();

        private void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            if (entries == null) {
                entries = new ArrayList<>();
            }
            for (RuntimeSynthesizedMemoryEntry entry : entries) {
                if (entry != null) {
                    entry.normalize();
                }
            }
            entries.removeIf(entry -> entry == null || entry.id.isBlank());
        }
    }

    public static final class RuntimeSynthesizedMemoryEntry {
        public String id = "";
        public String description = "";
        public List<String> focusTags = new ArrayList<>();
        public List<String> detailTags = new ArrayList<>();
        public String semanticTimeTag = "";
        public String semanticTimeLabel = "";
        public float importance;
        public long timestamp = -1L;
        public long occurredGameTime = -1L;
        public long occurredDayIndex = -1L;
        public long occurredTimeOfDayTick = -1L;
        public String dimensionId = "";
        public String biomeId = "";
        public Boolean raining;

        static RuntimeSynthesizedMemoryEntry fromEntry(dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry entry) {
            RuntimeSynthesizedMemoryEntry serialized = new RuntimeSynthesizedMemoryEntry();
            serialized.id = entry.getId() != null ? entry.getId().toString() : "";
            serialized.description = entry.getDescription() != null ? entry.getDescription() : "";
            for (FocusTag focusTag : entry.getFocusTags()) {
                serialized.focusTags.add(focusTag.name());
            }
            for (DetailTag detailTag : entry.getDetailTags()) {
                serialized.detailTags.add(detailTag.toString());
            }
            serialized.semanticTimeTag = entry.getSemanticTimeTag() != null
                    ? entry.getSemanticTimeTag().toString()
                    : "";
            serialized.semanticTimeLabel = entry.getSemanticTimeLabel() != null
                    ? entry.getSemanticTimeLabel()
                    : "";
            serialized.importance = entry.getImportance();
            serialized.timestamp = entry.getTimestamp();
            serialized.occurredGameTime = entry.getOccurredGameTime();
            serialized.occurredDayIndex = entry.getOccurredDayIndex();
            serialized.occurredTimeOfDayTick = entry.getOccurredTimeOfDayTick();
            serialized.dimensionId = entry.getDimensionId() != null ? entry.getDimensionId() : "";
            serialized.biomeId = entry.getBiomeId() != null ? entry.getBiomeId() : "";
            serialized.raining = entry.getRaining();
            return serialized;
        }

        void normalize() {
            if (id == null) {
                id = "";
            }
            if (description == null) {
                description = "";
            }
            if (focusTags == null) {
                focusTags = new ArrayList<>();
            }
            if (detailTags == null) {
                detailTags = new ArrayList<>();
            }
            if (semanticTimeTag == null) {
                semanticTimeTag = "";
            }
            if (semanticTimeLabel == null) {
                semanticTimeLabel = "";
            }
            if (dimensionId == null) {
                dimensionId = "";
            }
            if (biomeId == null) {
                biomeId = "";
            }
        }

        dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry toLtmEntry() {
            try {
                dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry.Builder builder =
                        dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry.builder()
                        .sourceType(dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry.SourceType.SYNTHESIZED)
                        .description(description)
                        .importance(importance)
                        .timestamp(timestamp)
                        .occurredGameTime(occurredGameTime)
                        .occurredDayIndex(occurredDayIndex)
                        .occurredTimeOfDayTick(occurredTimeOfDayTick)
                        .semanticTimeLabel(semanticTimeLabel)
                        .dimensionId(dimensionId)
                        .biomeId(biomeId)
                        .raining(raining);

                if (!id.isBlank()) {
                    builder.id(java.util.UUID.fromString(id));
                }
                if (!semanticTimeTag.isBlank()) {
                    builder.semanticTimeTag(dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy.parseRegisteredTag(semanticTimeTag));
                }
                for (String focusTag : focusTags) {
                    if (focusTag == null || focusTag.isBlank()) {
                        continue;
                    }
                    builder.focus(FocusTag.valueOf(focusTag));
                }
                for (String detailTag : detailTags) {
                    if (detailTag == null || detailTag.isBlank()) {
                        continue;
                    }
                    builder.detail(dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy.parseRegisteredTag(detailTag));
                }
                return builder.build();
            } catch (Exception e) {
                System.err.println(
                        "[TalkingWeapon/StudioRuntimeSynthesizedMemories] 跳过非法 synthesized memory: "
                                + id + " (" + e.getMessage() + ")");
                return null;
            }
        }
    }
}
