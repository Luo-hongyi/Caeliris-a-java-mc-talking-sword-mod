package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionTimelineRuntimeSettings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 玩家动作时间线在 Studio workspace 中的 authored 参数。
 * <p>
 * 当前只暴露两个控制项：
 * <ul>
 * <li>fixedSegmentSeconds：ActionArchive 固定切段长度</li>
 * <li>movingWindowSeconds：moving prompt window 的回看长度</li>
 * <li>registeredLocationLeaveExtraBlocks：已登记地点的离开半径额外扩张值</li>
 * <li>majorStructureEnterRadiusBlocks / majorStructureExitRadiusBlocks：major structure 进出判定半径</li>
 * <li>majorStructureWhitelist：基于 DetailTag 字符串的结构白名单</li>
 * </ul>
 */
public final class StudioActionTimelineTuningFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "action_timeline_tuning.json";
    public static final String VERSION_KEY = "actionTimelineTuning";
    public static final int SCHEMA_VERSION = 3;

    private StudioActionTimelineTuningFile() {
    }

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static synchronized ActionTimelineTuningFileModel readOrEmpty() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return createCurrentModel();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ActionTimelineTuningFileModel file = GSON.fromJson(reader, ActionTimelineTuningFileModel.class);
            if (file == null) {
                file = createCurrentModel();
            }
            file.normalize();
            return file;
        } catch (Exception e) {
            System.err.println("[TalkingWeapon/StudioActionTimelineTuning] 读取失败：" + e.getMessage());
            return createCurrentModel();
        }
    }

    public static synchronized boolean applyWorkspaceValuesToRuntime() {
        Path path = getPath();
        if (!Files.isRegularFile(path)) {
            return false;
        }
        applyToRuntime(readOrEmpty());
        return true;
    }

    public static synchronized void ensureWorkspaceFileExists() {
        if (!Files.isRegularFile(getPath())) {
            writeCurrent();
        }
    }

    public static synchronized void writeCurrent() {
        write(createCurrentModel());
    }

    public static synchronized void applyToRuntime(ActionTimelineTuningFileModel file) {
        ActionTimelineTuningFileModel safeFile = file != null ? file : createCurrentModel();
        safeFile.normalize();
        ActionTimelineRuntimeSettings.setFixedSegmentMs(safeFile.fixedSegmentSeconds * 1_000L);
        ActionTimelineRuntimeSettings.setMovingWindowMs(safeFile.movingWindowSeconds * 1_000L);
        ActionTimelineRuntimeSettings.setRegisteredLocationLeaveExtraBlocks(safeFile.registeredLocationLeaveExtraBlocks);
        ActionTimelineRuntimeSettings.setMajorStructureEnterRadiusBlocks(safeFile.majorStructureEnterRadiusBlocks);
        ActionTimelineRuntimeSettings.setMajorStructureExitRadiusBlocks(safeFile.majorStructureExitRadiusBlocks);
        ActionTimelineRuntimeSettings.setMajorStructureWhitelist(safeFile.majorStructureWhitelist);
    }

    private static ActionTimelineTuningFileModel createCurrentModel() {
        ActionTimelineTuningFileModel file = new ActionTimelineTuningFileModel();
        file.generatedAt = OffsetDateTime.now().toString();
        file.fixedSegmentSeconds = ActionTimelineRuntimeSettings.getFixedSegmentSeconds();
        file.movingWindowSeconds = ActionTimelineRuntimeSettings.getMovingWindowSeconds();
        file.registeredLocationLeaveExtraBlocks = ActionTimelineRuntimeSettings.getRegisteredLocationLeaveExtraBlocks();
        file.majorStructureEnterRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureEnterRadiusBlocks();
        file.majorStructureExitRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureExitRadiusBlocks();
        file.majorStructureWhitelist = new ArrayList<>(ActionTimelineRuntimeSettings.getMajorStructureWhitelist());
        file.normalize();
        return file;
    }

    private static void write(ActionTimelineTuningFileModel file) {
        Path path = getPath();
        ActionTimelineTuningFileModel safeFile = file != null ? file : createCurrentModel();
        safeFile.normalize();
        safeFile.generatedAt = OffsetDateTime.now().toString();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(safeFile, writer);
            }
            StudioWorkspaceManifestFile.markVersionUpdated(VERSION_KEY, StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioActionTimelineTuning] 写入失败：" + e.getMessage());
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class ActionTimelineTuningFileModel {
        public int schemaVersion = SCHEMA_VERSION;
        public String generatedAt = "";
        public int fixedSegmentSeconds = 60;
        public int movingWindowSeconds = 180;
        public int registeredLocationLeaveExtraBlocks = ActionTimelineRuntimeSettings.getRegisteredLocationLeaveExtraBlocks();
        public int majorStructureEnterRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureEnterRadiusBlocks();
        public int majorStructureExitRadiusBlocks = ActionTimelineRuntimeSettings.getMajorStructureExitRadiusBlocks();
        public List<String> majorStructureWhitelist =
                new ArrayList<>(ActionTimelineRuntimeSettings.getMajorStructureWhitelist());

        public void normalize() {
            boolean upgradingFromLegacySchema = schemaVersion > 0 && schemaVersion < SCHEMA_VERSION;
            if (schemaVersion <= 0) {
                schemaVersion = SCHEMA_VERSION;
            } else if (upgradingFromLegacySchema) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (generatedAt == null) {
                generatedAt = "";
            }
            fixedSegmentSeconds = clampInt(fixedSegmentSeconds, 10, 300);
            movingWindowSeconds = clampInt(movingWindowSeconds, 30, 480);
            if (upgradingFromLegacySchema && registeredLocationLeaveExtraBlocks <= 0) {
                registeredLocationLeaveExtraBlocks =
                        ActionTimelineRuntimeSettings.DEFAULT_REGISTERED_LOCATION_LEAVE_EXTRA_BLOCKS;
            }
            registeredLocationLeaveExtraBlocks = clampInt(registeredLocationLeaveExtraBlocks, 0, 256);
            if (upgradingFromLegacySchema && majorStructureEnterRadiusBlocks <= 0) {
                majorStructureEnterRadiusBlocks =
                        ActionTimelineRuntimeSettings.DEFAULT_MAJOR_STRUCTURE_ENTER_RADIUS_BLOCKS;
            }
            majorStructureEnterRadiusBlocks = clampInt(majorStructureEnterRadiusBlocks, 0, 256);
            if (upgradingFromLegacySchema && majorStructureExitRadiusBlocks <= 0) {
                majorStructureExitRadiusBlocks =
                        ActionTimelineRuntimeSettings.DEFAULT_MAJOR_STRUCTURE_EXIT_RADIUS_BLOCKS;
            }
            majorStructureExitRadiusBlocks = clampInt(
                    majorStructureExitRadiusBlocks,
                    majorStructureEnterRadiusBlocks,
                    256);
            List<String> sourceWhitelist = majorStructureWhitelist == null ? List.of() : majorStructureWhitelist;
            LinkedHashSet<String> normalizedWhitelist = new LinkedHashSet<>();
            for (String tag : sourceWhitelist) {
                if (tag == null) {
                    continue;
                }
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    normalizedWhitelist.add(trimmed);
                }
            }
            if (upgradingFromLegacySchema && normalizedWhitelist.isEmpty()) {
                normalizedWhitelist.addAll(ActionTimelineRuntimeSettings.DEFAULT_MAJOR_STRUCTURE_WHITELIST);
            }
            majorStructureWhitelist = new ArrayList<>(normalizedWhitelist);
        }
    }
}
