package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
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
import java.util.List;
import java.util.Locale;

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
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_ENTRIES = 96;

    private StudioRuntimeRecentInteractionsFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized RuntimeRecentInteractionsFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return new RuntimeRecentInteractionsFileModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeRecentInteractionsFileModel file = GSON.fromJson(reader, RuntimeRecentInteractionsFileModel.class);
            if (file == null) {
                file = new RuntimeRecentInteractionsFileModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeRecentInteractions] 读取失败：" + e.getMessage());
            return new RuntimeRecentInteractionsFileModel();
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
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
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
        public String userPrompt = "";
        public boolean hasImage;
        public List<String> actionTypes = new ArrayList<>();
        public List<RuntimeInteractionOutputEntry> outputs = new ArrayList<>();

        static RuntimeInteractionEntry fromTurn(InteractionTurn turn) {
            RuntimeInteractionEntry entry = new RuntimeInteractionEntry();
            entry.timestamp = turn.getTimestamp();
            entry.triggerSource = turn.getTriggerSource() != null ? turn.getTriggerSource() : "";
            entry.userPrompt = turn.getUserPrompt() != null ? turn.getUserPrompt() : "";
            entry.hasImage = turn.hasImage();
            entry.actionTypes = new ArrayList<>(turn.getActionTypes() != null ? turn.getActionTypes() : Collections.emptyList());
            for (AgentOutputRecord output : turn.getAgentActions()) {
                if (output == null) {
                    continue;
                }
                RuntimeInteractionOutputEntry outputEntry = new RuntimeInteractionOutputEntry();
                outputEntry.type = output.getType() != null
                        ? output.getType().name().toLowerCase(Locale.ROOT)
                        : "unknown";
                outputEntry.text = output.getText() != null ? output.getText() : "";
                outputEntry.timestamp = output.getTimestamp();
                entry.outputs.add(outputEntry);
            }
            entry.normalize();
            return entry;
        }

        private void normalize() {
            if (triggerSource == null) {
                triggerSource = "";
            }
            if (userPrompt == null) {
                userPrompt = "";
            }
            if (actionTypes == null) {
                actionTypes = new ArrayList<>();
            }
            if (outputs == null) {
                outputs = new ArrayList<>();
            }
            actionTypes.removeIf(type -> type == null || type.isBlank());
            for (RuntimeInteractionOutputEntry output : outputs) {
                if (output != null) {
                    output.normalize();
                }
            }
            outputs.removeIf(output -> output == null);
        }
    }

    public static final class RuntimeInteractionOutputEntry {
        public String type = "";
        public String text = "";
        public long timestamp;

        private void normalize() {
            if (type == null) {
                type = "";
            }
            if (text == null) {
                text = "";
            }
        }
    }
}
