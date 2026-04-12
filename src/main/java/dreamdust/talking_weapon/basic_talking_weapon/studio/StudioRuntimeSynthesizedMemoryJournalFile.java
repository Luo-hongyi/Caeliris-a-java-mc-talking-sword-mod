package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * synthesized LTM 的 append-only journal。
 * <p>
 * 每一行是一条独立 JSON 记录：
 * - {@code put}: 新增/写入一条 synthesized memory
 * - {@code remove}: 从当前集合中移除一条 synthesized memory
 */
public final class StudioRuntimeSynthesizedMemoryJournalFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_synthesized_memories.jsonl";

    private StudioRuntimeSynthesizedMemoryJournalFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized List<LTMEntry> readSynthesizedMemories() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return migrateFromSnapshotIfNeeded();
        }

        LinkedHashMap<String, LTMEntry> current = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder lineBuffer = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    char ch = buffer[i];
                    if (ch == '\n') {
                        replayLine(current, lineBuffer.toString());
                        lineBuffer.setLength(0);
                        continue;
                    }
                    if (ch != '\r') {
                        lineBuffer.append(ch);
                    }
                }
            }
            replayLine(current, lineBuffer.toString());
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeSynthesizedMemoryJournal] 读取失败：" + e.getMessage());
            return new ArrayList<>();
        }
        return new ArrayList<>(current.values());
    }

    public static synchronized void appendPut(LTMEntry entry) {
        if (entry == null || entry.getSourceType() != LTMEntry.SourceType.SYNTHESIZED || entry.getId() == null) {
            return;
        }
        JournalRecord record = JournalRecord.put(entry);
        appendAll(List.of(record));
    }

    public static synchronized void appendRemoves(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<JournalRecord> records = new ArrayList<>();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            records.add(JournalRecord.remove(id));
        }
        appendAll(records);
    }

    private static List<LTMEntry> migrateFromSnapshotIfNeeded() {
        StudioRuntimeSynthesizedMemoriesFile.RuntimeSynthesizedMemoriesFileModel snapshot =
                StudioRuntimeSynthesizedMemoriesFile.readOrEmpty();
        if (snapshot.entries.isEmpty()) {
            return new ArrayList<>();
        }

        List<LTMEntry> migrated = new ArrayList<>();
        for (StudioRuntimeSynthesizedMemoriesFile.RuntimeSynthesizedMemoryEntry entry : snapshot.entries) {
            if (entry == null) {
                continue;
            }
            LTMEntry synthesized = entry.toLtmEntry();
            if (synthesized != null) {
                migrated.add(synthesized);
            }
        }
        if (!migrated.isEmpty()) {
            List<JournalRecord> records = new ArrayList<>();
            for (LTMEntry entry : migrated) {
                records.add(JournalRecord.put(entry));
            }
            appendAll(records);
            System.out.println(
                    "[TalkingWeapon/StudioRuntimeSynthesizedMemoryJournal] 已从旧 snapshot 迁移 "
                            + migrated.size() + " 条 synthesized memories。");
        }
        return migrated;
    }

    private static void replayLine(LinkedHashMap<String, LTMEntry> current, String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return;
        }
        try {
            JournalRecord record = GSON.fromJson(rawLine, JournalRecord.class);
            if (record == null) {
                return;
            }
            record.normalize();
            if ("put".equals(record.op)) {
                if (record.memory == null || record.id.isBlank()) {
                    return;
                }
                LTMEntry entry = record.memory.toLtmEntry();
                if (entry == null) {
                    return;
                }
                current.remove(record.id);
                current.put(record.id, entry);
                return;
            }
            if ("remove".equals(record.op) && !record.id.isBlank()) {
                current.remove(record.id);
            }
        } catch (Exception e) {
            System.err.println(
                    "[TalkingWeapon/StudioRuntimeSynthesizedMemoryJournal] 跳过非法 journal 行："
                            + e.getMessage());
        }
    }

    private static void appendAll(List<JournalRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Path path = getPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                for (JournalRecord record : records) {
                    if (record == null) {
                        continue;
                    }
                    record.normalize();
                    writer.write(GSON.toJson(record));
                    writer.write('\n');
                }
            }
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeSynthesizedMemoryJournal] 写入失败：" + e.getMessage());
        }
    }

    public static final class JournalRecord {
        public String op = "";
        public String id = "";
        public StudioRuntimeSynthesizedMemoriesFile.RuntimeSynthesizedMemoryEntry memory;

        static JournalRecord put(LTMEntry entry) {
            JournalRecord record = new JournalRecord();
            record.op = "put";
            record.memory = StudioRuntimeSynthesizedMemoriesFile.RuntimeSynthesizedMemoryEntry.fromEntry(entry);
            record.id = record.memory != null ? record.memory.id : "";
            return record;
        }

        static JournalRecord remove(UUID id) {
            JournalRecord record = new JournalRecord();
            record.op = "remove";
            record.id = id != null ? id.toString() : "";
            return record;
        }

        void normalize() {
            if (op == null) {
                op = "";
            }
            if (id == null) {
                id = "";
            }
            if (memory != null) {
                memory.normalize();
                if (id.isBlank()) {
                    id = memory.id;
                }
            }
        }
    }
}
