package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio 工作区 manifest 的共享模型。
 * <p>
 * 该文件是 Studio 前端与游戏运行时之间的最小同步协议：
 * <ul>
 * <li>versions：告诉双方哪些文件版本发生了变化</li>
 * <li>updatedBy：告诉双方最近一次变更来自 Studio 还是 Minecraft 端</li>
 * </ul>
 */
public final class StudioWorkspaceManifestFile {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final String FILENAME = "studio_workspace.manifest.json";
    public static final int SCHEMA_VERSION = 1;
    public static final String UPDATED_BY_MINECRAFT = "minecraft";
    public static final String UPDATED_BY_STUDIO = "studio";
    public static final String UPDATED_BY_UNKNOWN = "unknown";

    public static final List<String> VERSION_KEYS = List.of(
            "snapshot",
            "runtimeTagView",
            "customTags",
            "descriptions",
            "relations",
            "personas",
            "memories",
            "agentState",
            "locations",
            "iconsDirectory");

    public static final List<String> EDITABLE_VERSION_KEYS = List.of(
            "customTags",
            "descriptions",
            "relations",
            "personas",
            "memories",
            "agentState",
            "locations");

    public int schemaVersion = SCHEMA_VERSION;
    public String generatedAt = "";
    public String updatedBy = UPDATED_BY_UNKNOWN;
    public int snapshotEntries;
    public int runtimeTagCount;
    public int runtimeIssues;
    public int renderedIcons;
    public int placeholderIcons;
    public List<String> files = new ArrayList<>();
    public Map<String, String> versions = createEmptyVersions();

    public static Path getPath() {
        return StudioWorkspacePaths.getWorkspaceRoot().resolve(FILENAME);
    }

    public static StudioWorkspaceManifestFile read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StudioWorkspaceManifestFile manifest = GSON.fromJson(reader, StudioWorkspaceManifestFile.class);
            if (manifest == null) {
                manifest = new StudioWorkspaceManifestFile();
            }
            manifest.normalize();
            return manifest;
        }
    }

    public static StudioWorkspaceManifestFile readIfExists(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        return read(path);
    }

    public static void write(Path path, StudioWorkspaceManifestFile manifest) throws IOException {
        StudioWorkspaceManifestFile safeManifest = manifest != null ? manifest : new StudioWorkspaceManifestFile();
        safeManifest.normalize();
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(safeManifest, writer);
        }
    }

    public static Map<String, String> createEmptyVersions() {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        for (String key : VERSION_KEYS) {
            versions.put(key, "missing");
        }
        return versions;
    }

    public static void markVersionUpdated(String versionKey, String updatedBy) {
        if (versionKey == null || versionKey.isBlank()) {
            return;
        }
        try {
            Path manifestPath = getPath();
            StudioWorkspaceManifestFile manifest = readIfExists(manifestPath);
            if (manifest == null) {
                manifest = new StudioWorkspaceManifestFile();
            }
            manifest.generatedAt = OffsetDateTime.now().toString();
            manifest.updatedBy = UPDATED_BY_MINECRAFT.equals(updatedBy) || UPDATED_BY_STUDIO.equals(updatedBy)
                    ? updatedBy
                    : UPDATED_BY_UNKNOWN;
            manifest.versions = manifest.normalizedVersions();
            manifest.versions.put(versionKey, Long.toString(System.currentTimeMillis()));
            write(manifestPath, manifest);
        } catch (IOException e) {
            System.err.println("[TalkingWeapon/StudioManifest] 更新版本戳失败：" + e.getMessage());
        }
    }

    public Map<String, String> normalizedVersions() {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>(createEmptyVersions());
        if (versions != null) {
            for (Map.Entry<String, String> entry : versions.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                normalized.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "missing");
            }
        }
        return normalized;
    }

    public String signature(List<String> keys, boolean includeUpdatedBy) {
        Map<String, String> normalized = normalizedVersions();
        StringBuilder sb = new StringBuilder();
        if (includeUpdatedBy) {
            sb.append(updatedBy != null ? updatedBy : UPDATED_BY_UNKNOWN);
        }
        for (String key : keys) {
            sb.append("|").append(key).append("=").append(normalized.getOrDefault(key, "missing"));
        }
        return sb.toString();
    }

    public void normalize() {
        if (schemaVersion <= 0) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (generatedAt == null) {
            generatedAt = "";
        }
        if (!UPDATED_BY_MINECRAFT.equals(updatedBy) && !UPDATED_BY_STUDIO.equals(updatedBy)) {
            updatedBy = UPDATED_BY_UNKNOWN;
        }
        if (files == null) {
            files = new ArrayList<>();
        }
        versions = normalizedVersions();
    }
}
