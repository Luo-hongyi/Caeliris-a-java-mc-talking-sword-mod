package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AttentionFilter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMRecallEngine;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.RecallRuntimeState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

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
 * 将最近一次长期记忆召回轨迹写入 Studio workspace。
 * <p>
 * 当前先持久化 trace lines + 基础结构化摘要，为后续 Studio 的思维导图页提供统一数据入口。
 */
public final class StudioRuntimeRecallTraceFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_recall_trace.json";
    public static final String VERSION_KEY = "runtimeRecallTrace";
    public static final int SCHEMA_VERSION = 2;

    private StudioRuntimeRecallTraceFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeRecallTraceFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return new RuntimeRecallTraceFileModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeRecallTraceFileModel file = GSON.fromJson(reader, RuntimeRecallTraceFileModel.class);
            if (file == null) {
                file = new RuntimeRecallTraceFileModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeRecallTrace] 读取失败：" + e.getMessage());
            return new RuntimeRecallTraceFileModel();
        }
    }

    public static synchronized void writeLatest(AgentContext ctx, String userPrompt, List<TaggedContextEntry> visibleContexts,
            AgentState state, LTMRecallEngine.RecallResult recallResult) {
        RuntimeRecallTraceFileModel file = new RuntimeRecallTraceFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.triggerSource = ctx != null && ctx.getTriggerSource() != null ? ctx.getTriggerSource() : "";
        file.userPrompt = userPrompt != null ? userPrompt : "";
        file.recallPreset = state != null && state.getRecallPreset() != null ? state.getRecallPreset().name() : "";
        file.currentDesireTag = state != null && state.getCurrentDesireTag() != null ? state.getCurrentDesireTag().name() : "";

        if (visibleContexts != null) {
            for (TaggedContextEntry entry : visibleContexts) {
                if (entry == null) {
                    continue;
                }
                VisibleContextEntry contextEntry = new VisibleContextEntry();
                contextEntry.description = entry.getDescription();
                contextEntry.baseImportance = entry.getBaseImportance();
                contextEntry.finalScore = state != null
                        ? AttentionFilter.calculateFinalScore(entry, state.getConcentration())
                        : entry.getBaseImportance();
                for (var focusTag : entry.getFocusTags()) {
                    contextEntry.focusTags.add(focusTag.name());
                }
                for (DetailTag detailTag : entry.getDetailTags()) {
                    contextEntry.detailTags.add(detailTag.toString());
                }
                file.visibleContexts.add(contextEntry);
            }
        }

        if (recallResult != null) {
            file.triggerTags.addAll(recallResult.triggerTags);
            file.traceLines.addAll(recallResult.traceLines);
            file.runtimeState = recallResult.runtimeState != null
                    ? recallResult.runtimeState
                    : new RecallRuntimeState.Snapshot();
            for (LTMEntry memory : recallResult.recalledMemories) {
                if (memory == null) {
                    continue;
                }
                RecalledMemoryEntry entry = new RecalledMemoryEntry();
                entry.id = memory.getId() != null ? memory.getId().toString() : "";
                entry.description = memory.getDescription();
                for (var focusTag : memory.getFocusTags()) {
                    entry.focusTags.add(focusTag.name());
                }
                for (DetailTag detailTag : memory.getDetailTags()) {
                    entry.detailTags.add(detailTag.toString());
                }
                entry.semanticTimeTag = memory.getSemanticTimeTag() != null ? memory.getSemanticTimeTag().toString() : "";
                entry.semanticTimeLabel = memory.getSemanticTimeLabel() != null ? memory.getSemanticTimeLabel() : "";
                file.recalledMemories.add(entry);
            }
        }

        write(file);
    }

    private static void write(RuntimeRecallTraceFileModel file) {
        Path path = getPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeRecallTrace] 写入失败：" + e.getMessage());
        }
    }

    public static final class RuntimeRecallTraceFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public String triggerSource = "";
        public String userPrompt = "";
        public String recallPreset = "";
        public String currentDesireTag = "";
        public List<String> triggerTags = new ArrayList<>();
        public List<String> traceLines = new ArrayList<>();
        public List<VisibleContextEntry> visibleContexts = new ArrayList<>();
        public List<RecalledMemoryEntry> recalledMemories = new ArrayList<>();
        public RecallRuntimeState.Snapshot runtimeState = new RecallRuntimeState.Snapshot();

        private void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            if (triggerSource == null) {
                triggerSource = "";
            }
            if (userPrompt == null) {
                userPrompt = "";
            }
            if (recallPreset == null) {
                recallPreset = "";
            }
            if (currentDesireTag == null) {
                currentDesireTag = "";
            }
            if (triggerTags == null) {
                triggerTags = new ArrayList<>();
            }
            if (traceLines == null) {
                traceLines = new ArrayList<>();
            }
            if (visibleContexts == null) {
                visibleContexts = new ArrayList<>();
            }
            if (recalledMemories == null) {
                recalledMemories = new ArrayList<>();
            }
            if (runtimeState == null) {
                runtimeState = new RecallRuntimeState.Snapshot();
            }
        }
    }

    public static final class VisibleContextEntry {
        public String description = "";
        public List<String> focusTags = new ArrayList<>();
        public List<String> detailTags = new ArrayList<>();
        public float baseImportance;
        public float finalScore;
    }

    public static final class RecalledMemoryEntry {
        public String id = "";
        public String description = "";
        public List<String> focusTags = new ArrayList<>();
        public List<String> detailTags = new ArrayList<>();
        public String semanticTimeTag = "";
        public String semanticTimeLabel = "";
    }
}
