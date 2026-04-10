package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSourceType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessageKind;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将最近的交互回合持久化到 Studio workspace。
 * <p>
 * 它不是 authored 真源，而是给 Studio/游戏调试页共享的只读 runtime 参考文件。
 */
public final class StudioRuntimeRecentInteractionsFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_recent_interactions.json";
    public static final String VERSION_KEY = "runtimeInteractions";
    public static final int SCHEMA_VERSION = 6;
    private static final int MAX_ENTRIES = 96;
    private static RuntimeRecentInteractionsFileModel cachedFile;

    private StudioRuntimeRecentInteractionsFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeRecentInteractionsFileModel readOrEmpty() {
        if (cachedFile != null) {
            return cachedFile;
        }
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            cachedFile = new RuntimeRecentInteractionsFileModel();
            return cachedFile;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeRecentInteractionsFileModel file = GSON.fromJson(reader, RuntimeRecentInteractionsFileModel.class);
            if (file == null) {
                file = new RuntimeRecentInteractionsFileModel();
            }
            if (file.schemaVersion != SCHEMA_VERSION) {
                cachedFile = new RuntimeRecentInteractionsFileModel();
                return cachedFile;
            }
            file.normalize();
            cachedFile = file;
            return cachedFile;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeRecentInteractions] 读取失败：" + e.getMessage());
            cachedFile = new RuntimeRecentInteractionsFileModel();
            return cachedFile;
        }
    }

    public static synchronized void recordTurn(InteractionTurn turn) {
        if (turn == null) {
            return;
        }
        RuntimeRecentInteractionsFileModel file = readOrEmpty();
        file.generatedAt = OffsetDateTime.now().toString();
        file.entries.add(RuntimeInteractionEntry.fromTurn(turn));
        while (file.entries.size() > MAX_ENTRIES) {
            file.entries.remove(0);
        }
        write(file);
    }

    private static void write(RuntimeRecentInteractionsFileModel file) {
        Path path = getPath();
        try {
            file.normalize();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            cachedFile = file;
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeRecentInteractions] 写入失败：" + e.getMessage());
        }
    }

    public static final class RuntimeRecentInteractionsFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public List<RuntimeInteractionEntry> entries = new ArrayList<>();

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
            for (RuntimeInteractionEntry entry : entries) {
                if (entry != null) {
                    entry.normalize();
                }
            }
            entries.removeIf(entry -> entry == null);
        }
    }

    public static final class RuntimeInteractionEntry {
        public long timestamp;
        public String triggerSource = "";
        public boolean hasImage;
        public List<RuntimeInteractionMessageEntry> messages = new ArrayList<>();
        public Map<String, String> contextExtras = new LinkedHashMap<>();
        public List<RuntimeInteractionVisibleContextEntry> visibleContexts = new ArrayList<>();

        static RuntimeInteractionEntry fromTurn(InteractionTurn turn) {
            RuntimeInteractionEntry entry = new RuntimeInteractionEntry();
            entry.timestamp = turn.getTimestamp();
            entry.triggerSource = turn.getTriggerSource() != null ? turn.getTriggerSource() : "";
            entry.hasImage = turn.hasImage();
            entry.messages = new ArrayList<>();
            entry.contextExtras = new LinkedHashMap<>(
                    turn.getContextExtras() != null ? turn.getContextExtras() : Collections.emptyMap());
            for (InteractionMessage message : turn.getMessages()) {
                if (message == null || message.getSource() == null || message.getKind() == null) {
                    continue;
                }
                RuntimeInteractionMessageEntry messageEntry = new RuntimeInteractionMessageEntry();
                messageEntry.sourceType = message.getSource().getType();
                messageEntry.sourceId = message.getSource().getSourceId();
                messageEntry.sourceDisplayName = message.getSource().getDisplayName();
                messageEntry.kind = message.getKind();
                messageEntry.text = message.getText() != null ? message.getText() : "";
                entry.messages.add(messageEntry);
            }
            for (TaggedContextEntry visibleContext : turn.getVisibleContexts()) {
                if (visibleContext == null) {
                    continue;
                }
                RuntimeInteractionVisibleContextEntry visibleEntry = new RuntimeInteractionVisibleContextEntry();
                visibleEntry.description = visibleContext.getDescription() != null ? visibleContext.getDescription() : "";
                visibleEntry.timestamp = visibleContext.getTimestamp();
                if (visibleContext.getSource() != null) {
                    visibleEntry.sourceType = visibleContext.getSource().getType().name();
                    visibleEntry.sourceId = visibleContext.getSource().getSourceId();
                    visibleEntry.sourceDisplayName = visibleContext.getSource().getDisplayName();
                }
                entry.visibleContexts.add(visibleEntry);
            }
            entry.normalize();
            return entry;
        }

        private void normalize() {
            if (triggerSource == null) {
                triggerSource = "";
            }
            if (messages == null) {
                messages = new ArrayList<>();
            }
            if (contextExtras == null) {
                contextExtras = new LinkedHashMap<>();
            }
            if (visibleContexts == null) {
                visibleContexts = new ArrayList<>();
            }
            for (RuntimeInteractionMessageEntry message : messages) {
                if (message != null) {
                    message.normalize();
                }
            }
            messages.removeIf(message -> message == null || message.sourceType == null || message.kind == null);
            contextExtras.entrySet().removeIf(entry ->
                    entry == null
                            || entry.getKey() == null
                            || entry.getKey().isBlank()
                            || entry.getValue() == null);
            for (RuntimeInteractionVisibleContextEntry visibleContext : visibleContexts) {
                if (visibleContext != null) {
                    visibleContext.normalize();
                }
            }
            visibleContexts.removeIf(visibleContext -> visibleContext == null);
        }
    }

    public static final class RuntimeInteractionMessageEntry {
        public TaggedContextSourceType sourceType;
        public String sourceId = "";
        public String sourceDisplayName = "";
        public InteractionMessageKind kind;
        public String text = "";

        private void normalize() {
            if (sourceId == null) {
                sourceId = "";
            }
            if (sourceDisplayName == null) {
                sourceDisplayName = "";
            }
            if (text == null) {
                text = "";
            }
        }

        public TaggedContextSource toSource() {
            if (sourceType == null) {
                return null;
            }
            return switch (sourceType) {
                case PLAYER -> TaggedContextSource.player(sourceId, sourceDisplayName);
                case AGENT -> TaggedContextSource.agent(sourceId.isBlank() ? "self" : sourceId, sourceDisplayName);
                case ENVIRONMENT -> TaggedContextSource.environment(
                        sourceId.isBlank() ? "environment" : sourceId,
                        sourceDisplayName);
            };
        }
    }

    public static final class RuntimeInteractionVisibleContextEntry {
        public String description = "";
        public long timestamp;
        public String sourceType = "";
        public String sourceId = "";
        public String sourceDisplayName = "";

        private void normalize() {
            if (description == null) {
                description = "";
            }
            if (sourceType == null) {
                sourceType = "";
            }
            if (sourceId == null) {
                sourceId = "";
            }
            if (sourceDisplayName == null) {
                sourceDisplayName = "";
            }
        }
    }

}
