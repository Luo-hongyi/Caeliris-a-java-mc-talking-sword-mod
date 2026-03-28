package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedSegmentDiscardWindow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * fixed segment discard windows 的独立 sidecar log。
 * <p>
 * 目标保持简单：
 * <ul>
 * <li>正常运行时只追加新 discard window</li>
 * <li>startup/manual compact 后若窗口清空，则直接清空该文件</li>
 * <li>只有出现非追加式变化时才整份重写</li>
 * </ul>
 */
public final class StudioRuntimeActionDiscardLogFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_action_segment_discards.jsonl";
    public static final String VERSION_KEY = "runtimeActionSegmentDiscardLog";

    private static List<FixedSegmentDiscardWindow> cachedDiscardWindows;

    private StudioRuntimeActionDiscardLogFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized List<FixedSegmentDiscardWindow> readDiscardWindowsOrEmpty() {
        return List.copyOf(readDiscardWindowsViewOrEmpty());
    }

    public static synchronized boolean syncCurrentDiscardWindows(List<FixedSegmentDiscardWindow> discardWindows) {
        List<FixedSegmentDiscardWindow> current = normalize(discardWindows);
        List<FixedSegmentDiscardWindow> previous = readDiscardWindowsViewOrEmpty();
        if (sameWindows(previous, current)) {
            return true;
        }

        Path path = getPath();
        try {
            if (current.isEmpty()) {
                Files.deleteIfExists(path);
            } else if (canAppend(previous, current)) {
                appendDiscardWindows(path, current.subList(previous.size(), current.size()));
            } else {
                rewriteDiscardWindows(path, current);
            }
            cachedDiscardWindows = new ArrayList<>(current);
            StudioWorkspaceManifestFile.markVersionUpdated(
                    VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
            return true;
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionDiscardLog] 写入失败：" + e.getMessage());
            return false;
        }
    }

    private static List<FixedSegmentDiscardWindow> readDiscardWindowsViewOrEmpty() {
        if (cachedDiscardWindows != null) {
            return cachedDiscardWindows;
        }

        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            cachedDiscardWindows = new ArrayList<>();
            return cachedDiscardWindows;
        }

        ArrayList<FixedSegmentDiscardWindow> loaded = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             BufferedReader bufferedReader = reader instanceof BufferedReader br ? br : new BufferedReader(reader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                PersistedDiscardWindowLine persisted =
                        GSON.fromJson(trimmed, PersistedDiscardWindowLine.class);
                if (persisted == null) {
                    continue;
                }
                FixedSegmentDiscardWindow restored = persisted.toDiscardWindow();
                if (restored != null) {
                    loaded.add(restored);
                }
            }
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionDiscardLog] 读取失败：" + e.getMessage());
            cachedDiscardWindows = new ArrayList<>();
            return cachedDiscardWindows;
        }

        cachedDiscardWindows = loaded;
        return cachedDiscardWindows;
    }

    private static List<FixedSegmentDiscardWindow> normalize(List<FixedSegmentDiscardWindow> discardWindows) {
        if (discardWindows == null || discardWindows.isEmpty()) {
            return List.of();
        }
        ArrayList<FixedSegmentDiscardWindow> normalized = new ArrayList<>(discardWindows.size());
        for (FixedSegmentDiscardWindow discardWindow : discardWindows) {
            if (discardWindow != null) {
                normalized.add(discardWindow);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static boolean sameWindows(
            List<FixedSegmentDiscardWindow> left,
            List<FixedSegmentDiscardWindow> right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!sameWindow(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean canAppend(
            List<FixedSegmentDiscardWindow> previous,
            List<FixedSegmentDiscardWindow> current) {
        if (current == null || current.isEmpty()) {
            return false;
        }
        if (previous == null || previous.isEmpty()) {
            return true;
        }
        if (current.size() < previous.size()) {
            return false;
        }
        for (int i = 0; i < previous.size(); i++) {
            if (!sameWindow(previous.get(i), current.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameWindow(
            FixedSegmentDiscardWindow left,
            FixedSegmentDiscardWindow right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.startSegmentId() == right.startSegmentId()
                && left.endSegmentId() == right.endSegmentId()
                && left.startTimestamp() == right.startTimestamp()
                && left.endTimestamp() == right.endTimestamp()
                && left.createdAt() == right.createdAt();
    }

    private static void rewriteDiscardWindows(
            Path path,
            List<FixedSegmentDiscardWindow> discardWindows) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeDiscardWindows(writer, discardWindows);
        }
    }

    private static void appendDiscardWindows(
            Path path,
            List<FixedSegmentDiscardWindow> discardWindows) throws IOException {
        if (discardWindows == null || discardWindows.isEmpty()) {
            return;
        }
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE)) {
            writeDiscardWindows(writer, discardWindows);
        }
    }

    private static void writeDiscardWindows(
            Writer writer,
            List<FixedSegmentDiscardWindow> discardWindows) throws IOException {
        if (discardWindows == null || discardWindows.isEmpty()) {
            return;
        }
        for (FixedSegmentDiscardWindow discardWindow : discardWindows) {
            if (discardWindow == null) {
                continue;
            }
            PersistedDiscardWindowLine persisted = PersistedDiscardWindowLine.from(discardWindow);
            writer.write(GSON.toJson(persisted));
            writer.write('\n');
        }
    }

    private static final class PersistedDiscardWindowLine {
        public long startSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long endSegmentId = FixedActionSegment.UNASSIGNED_SEGMENT_ID;
        public long startTimestamp;
        public long endTimestamp;
        public long createdAt;

        private static PersistedDiscardWindowLine from(FixedSegmentDiscardWindow discardWindow) {
            PersistedDiscardWindowLine line = new PersistedDiscardWindowLine();
            if (discardWindow == null) {
                return line;
            }
            line.startSegmentId = discardWindow.startSegmentId();
            line.endSegmentId = discardWindow.endSegmentId();
            line.startTimestamp = discardWindow.startTimestamp();
            line.endTimestamp = discardWindow.endTimestamp();
            line.createdAt = discardWindow.createdAt();
            return line;
        }

        private FixedSegmentDiscardWindow toDiscardWindow() {
            if (startSegmentId <= 0L || endSegmentId <= 0L) {
                return null;
            }
            return new FixedSegmentDiscardWindow(
                    startSegmentId,
                    endSegmentId,
                    Math.max(0L, startTimestamp),
                    Math.max(Math.max(0L, startTimestamp), endTimestamp),
                    Math.max(0L, createdAt));
        }
    }
}
