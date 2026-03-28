package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * sealed fixed segments 的正式持久化文件。
 * <p>
 * 目的不是替代 runtime timeline，而是把体积最大的 sealed segment 列表从高频 snapshot 文件中拆出去：
 * <ul>
 * <li>runtime timeline 继续高频刷新，但尽量只承载轻量运行时状态</li>
 * <li>segment store 仅在 sealed segments 真正变化时才重写</li>
 * </ul>
 */
public final class StudioRuntimeActionSegmentStoreFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_runtime_action_segments.json";
    public static final String DATA_FILENAME = "studio_runtime_action_segments.data.jsonl";
    public static final String VERSION_KEY = "runtimeActionSegmentStore";
    public static final int SCHEMA_VERSION = 2;

    private static RuntimeActionSegmentStoreFileModel cachedFile;

    private StudioRuntimeActionSegmentStoreFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static Path getDataPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(DATA_FILENAME);
    }

    private static Path resolveDataPath(String dataFilename) {
        String effective = dataFilename == null || dataFilename.isBlank() ? DATA_FILENAME : dataFilename;
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(effective);
    }

    public static synchronized RuntimeActionSegmentStoreFileModel readOrEmpty() {
        if (cachedFile != null) {
            return cachedFile;
        }

        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            cachedFile = new RuntimeActionSegmentStoreFileModel();
            cachedFile.normalize();
            return cachedFile;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RuntimeActionSegmentStoreFileModel file = GSON.fromJson(reader, RuntimeActionSegmentStoreFileModel.class);
            if (file == null) {
                file = new RuntimeActionSegmentStoreFileModel();
            }
            file.normalize();
            file.fixedSegments = loadFixedSegments(file);
            cachedFile = file;
            return cachedFile;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionSegments] 读取失败：" + e.getMessage());
            cachedFile = new RuntimeActionSegmentStoreFileModel();
            cachedFile.normalize();
            return cachedFile;
        }
    }

    public static synchronized List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> readFixedSegmentsOrEmpty() {
        RuntimeActionSegmentStoreFileModel file = readOrEmpty();
        return file.fixedSegments == null ? List.of() : List.copyOf(file.fixedSegments);
    }

    static synchronized List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> readFixedSegmentsViewOrEmpty() {
        RuntimeActionSegmentStoreFileModel file = readOrEmpty();
        return file.fixedSegments == null ? List.of() : file.fixedSegments;
    }

    public static synchronized boolean writeCurrentSegments(
            String sequenceToken,
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> fixedSegments) {
        RuntimeActionSegmentStoreFileModel previous = readOrEmpty();
        String nextSequenceToken = sequenceToken == null ? "" : sequenceToken;
        if (Objects.equals(previous.sequenceToken, nextSequenceToken)) {
            return true;
        }
        List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> nextSegments =
                fixedSegments == null ? List.of() : List.copyOf(fixedSegments);
        boolean appendOnly = canAppend(previous.fixedSegments, nextSegments);
        try {
            writeData(nextSegments, previous.fixedSegments == null ? List.of() : previous.fixedSegments, appendOnly);
            RuntimeActionSegmentStoreFileModel nextFile = RuntimeActionSegmentStoreFileModel.from(
                    nextSequenceToken,
                    nextSegments.size(),
                    DATA_FILENAME);
            writeMeta(nextFile);
            nextFile.fixedSegments = new ArrayList<>(nextSegments);
            cachedFile = nextFile;
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY,
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
            return true;
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionSegments] 写入失败：" + e.getMessage());
            return false;
        }
    }

    private static void writeMeta(RuntimeActionSegmentStoreFileModel file) throws IOException {
        Path path = getPath();
        RuntimeActionSegmentStoreFileModel persisted = RuntimeActionSegmentStoreFileModel.from(
                file.sequenceToken,
                file.fixedSegmentCount,
                file.dataFilename);
        persisted.normalize();
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(persisted, writer);
        }
    }

    private static void writeData(
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> nextSegments,
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> previousSegments,
            boolean appendOnly) throws IOException {
        Path dataPath = getDataPath();
        Files.createDirectories(dataPath.getParent());
        if (appendOnly) {
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> appendedSegments =
                    nextSegments.subList(previousSegments.size(), nextSegments.size());
            appendSegments(dataPath, appendedSegments);
            return;
        }
        rewriteSegments(dataPath, nextSegments);
    }

    private static void rewriteSegments(
            Path dataPath,
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> segments) throws IOException {
        try (Writer writer = Files.newBufferedWriter(
                dataPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeSegments(writer, segments);
        }
    }

    private static void appendSegments(
            Path dataPath,
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> segments) throws IOException {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(
                dataPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE)) {
            writeSegments(writer, segments);
        }
    }

    private static void writeSegments(
            Writer writer,
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> segments) throws IOException {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        for (StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel segment : segments) {
            if (segment == null) {
                continue;
            }
            segment.normalize();
            writer.write(GSON.toJson(segment));
            writer.write('\n');
        }
    }

    private static List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> loadFixedSegments(
            RuntimeActionSegmentStoreFileModel file) {
        if (file == null) {
            return List.of();
        }
        Path dataPath = resolveDataPath(file.dataFilename);
        if (!Files.isRegularFile(dataPath)) {
            return List.of();
        }

        ArrayList<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(dataPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel model =
                        GSON.fromJson(trimmed, StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel.class);
                if (model == null) {
                    continue;
                }
                model.normalize();
                loaded.add(model);
            }
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioRuntimeActionSegments] 读取 data 失败：" + e.getMessage());
            return List.of();
        }
        return List.copyOf(loaded);
    }

    private static boolean canAppend(
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> previous,
            List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> next) {
        if (previous == null || previous.isEmpty() || next == null || next.size() <= previous.size()) {
            return false;
        }
        for (int i = 0; i < previous.size(); i++) {
            if (!sameSegmentIdentity(previous.get(i), next.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSegmentIdentity(
            StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel left,
            StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.index == right.index
                && left.segmentId == right.segmentId
                && left.startTimestamp == right.startTimestamp
                && left.endTimestamp == right.endTimestamp
                && left.rawStartIndex == right.rawStartIndex
                && left.rawEndIndex == right.rawEndIndex;
    }

    public static final class RuntimeActionSegmentStoreFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public String sequenceToken = "";
        public int fixedSegmentCount;
        public String dataFilename = DATA_FILENAME;
        public transient List<StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel> fixedSegments = new ArrayList<>();

        public static RuntimeActionSegmentStoreFileModel from(
                String sequenceToken,
                int fixedSegmentCount,
                String dataFilename) {
            RuntimeActionSegmentStoreFileModel model = new RuntimeActionSegmentStoreFileModel();
            model.generatedAt = OffsetDateTime.now().toString();
            model.sequenceToken = sequenceToken == null ? "" : sequenceToken;
            model.fixedSegmentCount = Math.max(0, fixedSegmentCount);
            model.dataFilename = dataFilename == null || dataFilename.isBlank() ? DATA_FILENAME : dataFilename;
            model.normalize();
            return model;
        }

        public void normalize() {
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            }
            generatedAt = generatedAt == null ? "" : generatedAt;
            sequenceToken = sequenceToken == null ? "" : sequenceToken;
            dataFilename = dataFilename == null || dataFilename.isBlank() ? DATA_FILENAME : dataFilename;
            fixedSegments = fixedSegments == null ? new ArrayList<>() : new ArrayList<>(fixedSegments);
            for (StudioRuntimeActionTimelineFile.RuntimeFixedSegmentModel fixedSegment : fixedSegments) {
                if (fixedSegment != null) {
                    fixedSegment.normalize();
                }
            }
            fixedSegmentCount = fixedSegmentCount > 0 ? fixedSegmentCount : fixedSegments.size();
        }
    }
}
