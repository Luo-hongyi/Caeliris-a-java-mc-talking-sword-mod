package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceManifestFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 关键位置注册表：保存玩家与 Agent 共同维护的命名地标。
 * <p>
 * 从 {@code config/talking_weapon/studio_workspace/location_registry.json} 加载，
 * 首次运行时自动从 jar 内资源复制默认配置。
 * <p>
 * <b>用途</b>：当 Collector 或触发器产出 {@code custom_specific:*} 且值命中地点名时，
 * 可在此查询地标对应的坐标与维度，并注入 LLM Prompt。
 * 也可用于未来的"返回家"等指令。
 * <p>
 * <b>持久化</b>：地图支持运行时增删条目并保存回 JSON（侧重游戏内命令未来扩展）。
 */
public final class LocationRegistry {

    private static final String LOG_PREFIX = "[TalkingWeapon/LocationRegistry] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int DEFAULT_HORIZONTAL_RADIUS = 24;
    public static final int DEFAULT_VERTICAL_RADIUS = 16;

    private static final String CONFIG_FILENAME = "location_registry.json";
    private static final String DEFAULT_RESOURCE = "/assets/talking_weapon/default_location_registry.json";

    // ─────────────────────────────────────────────
    // 数据模型
    // ─────────────────────────────────────────────

    /** 单个地点记录 */
    public static final class LocationEntry {
        public int x;
        public int y;
        public int z;
        public String dim; // 维度 ID，如 "minecraft:overworld"
        public String note; // 备注
        public int horizontalRadius; // 水平命中半径
        public int verticalRadius; // 垂直命中半径
        public String focusTag; // 关联的 FocusTag 名称（如 "LANDSCAPE"），可为空
        public String detailTags; // 逗号分隔的 DetailTag 列表（如 "custom_generic:safe"），可为空

        public LocationEntry() {
        }

        public LocationEntry(int x, int y, int z, String dim, String note) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.note = note;
            this.horizontalRadius = DEFAULT_HORIZONTAL_RADIUS;
            this.verticalRadius = DEFAULT_VERTICAL_RADIUS;
        }

        public LocationEntry(int x, int y, int z, String dim, String note, String focusTag, String detailTags) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.note = note;
            this.horizontalRadius = DEFAULT_HORIZONTAL_RADIUS;
            this.verticalRadius = DEFAULT_VERTICAL_RADIUS;
            this.focusTag = focusTag;
            this.detailTags = detailTags;
        }

        /** 返回给 LLM 的自然语言坐标描述 */
        public String toPromptString() {
            return String.format("(%d, %d, %d) [%s]%s",
                    x, y, z, dim,
                    (note != null && !note.isBlank() ? " — " + note : ""));
        }

        /** 获取解析后的 DetailTag 字符串列表 */
        public List<String> getParsedDetailTags() {
            if (detailTags == null || detailTags.isBlank())
                return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (String s : detailTags.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty())
                    result.add(trimmed);
            }
            return result;
        }

        @Override
        public String toString() {
            return toPromptString();
        }
    }

    // ─────────────────────────────────────────────
    // 单例
    // ─────────────────────────────────────────────

    private static LocationRegistry INSTANCE;

    private final Map<String, LocationEntry> locations;

    private LocationRegistry(Map<String, LocationEntry> locations) {
        this.locations = locations;
    }

    public static synchronized LocationRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已重新加载，共 " + INSTANCE.locations.size() + " 个地点。");
    }

    public static final class ResolvedLocation {
        private final String name;
        private final LocationEntry entry;
        private final double horizontalDistance;
        private final double verticalDistance;

        private ResolvedLocation(String name, LocationEntry entry, double horizontalDistance, double verticalDistance) {
            this.name = name;
            this.entry = entry;
            this.horizontalDistance = horizontalDistance;
            this.verticalDistance = verticalDistance;
        }

        public String getName() {
            return name;
        }

        public LocationEntry getEntry() {
            return entry;
        }

        public double getHorizontalDistance() {
            return horizontalDistance;
        }

        public double getVerticalDistance() {
            return verticalDistance;
        }

        public String toPromptString() {
            return name + ": " + entry.toPromptString();
        }
    }

    // ─────────────────────────────────────────────
    // 查询 API
    // ─────────────────────────────────────────────

    /**
     * 按名称查询地点。
     *
     * @param name 地点名（如 {@code "home"}, {@code "base"}）
     * @return 地点记录；不存在时返回 {@code null}
     */
    public LocationEntry get(String name) {
        return locations.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 返回地点的 Prompt 友好描述，如 "(100, 64, -200) [minecraft:overworld] — 主要居所"。
     * 若不存在则返回 {@code null}。
     */
    public String describeLocation(String name) {
        LocationEntry entry = get(name);
        return entry != null ? entry.toPromptString() : null;
    }

    /** 所有已注册的地点名称（只读） */
    public Set<String> allNames() {
        return Collections.unmodifiableSet(locations.keySet());
    }

    /** 是否包含某个地点 */
    public boolean contains(String name) {
        return locations.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public ResolvedLocation resolveCurrentLocation(double x, double y, double z, String dim,
            double horizontalRadius, double verticalRadius) {
        if (dim == null || dim.isBlank()) {
            return null;
        }
        String normalizedDim = dim.trim();
        ResolvedLocation best = null;
        for (Map.Entry<String, LocationEntry> candidate : locations.entrySet()) {
            LocationEntry entry = candidate.getValue();
            if (entry == null || !normalizedDim.equals(entry.dim)) {
                continue;
            }
            double dx = x - entry.x;
            double dz = z - entry.z;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            double effectiveHorizontalRadius = entry.horizontalRadius > 0 ? entry.horizontalRadius : horizontalRadius;
            if (horizontalDistance > effectiveHorizontalRadius) {
                continue;
            }
            double verticalDistance = Math.abs(y - entry.y);
            double effectiveVerticalRadius = entry.verticalRadius > 0 ? entry.verticalRadius : verticalRadius;
            if (verticalDistance > effectiveVerticalRadius) {
                continue;
            }
            if (best == null
                    || horizontalDistance < best.horizontalDistance
                    || (horizontalDistance == best.horizontalDistance && verticalDistance < best.verticalDistance)) {
                best = new ResolvedLocation(candidate.getKey(), entry, horizontalDistance, verticalDistance);
            }
        }
        return best;
    }

    // ─────────────────────────────────────────────
    // 运行时写入（供未来命令系统调用）
    // ─────────────────────────────────────────────

    /**
     * 新增或更新一个地点，并立即持久化到 JSON。
     */
    public synchronized void put(String name, LocationEntry entry) {
        locations.put(normalizeName(name), normalizeEntry(entry));
        save();
        System.out.println(LOG_PREFIX + "更新地点 '" + name + "' → " + entry.toPromptString());
    }

    /**
     * 删除一个地点，并立即持久化到 JSON。
     *
     * @return true 若该地点存在且已删除
     */
    public synchronized boolean remove(String name) {
        boolean existed = locations.remove(normalizeName(name)) != null;
        if (existed) {
            save();
        }
        return existed;
    }

    /**
     * 原子替换全部地点条目，并只写盘一次。
     */
    public synchronized void updateAll(Map<String, LocationEntry> newLocations) {
        locations.clear();
        if (newLocations != null) {
            for (Map.Entry<String, LocationEntry> entry : newLocations.entrySet()) {
                String name = normalizeName(entry.getKey());
                if (name.isBlank()) {
                    continue;
                }
                locations.put(name, normalizeEntry(entry.getValue()));
            }
        }
        save();
        System.out.println(LOG_PREFIX + "已整体更新，共 " + locations.size() + " 个地点。");
    }

    // ─────────────────────────────────────────────
    // 加载 / 保存
    // ─────────────────────────────────────────────

    private static LocationRegistry load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try {
            Map<String, LocationEntry> map = readEntries(configPath);
            System.out.println(LOG_PREFIX + "加载完成，共 " + map.size() + " 个地点：" + configPath);
            return new LocationRegistry(map);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空注册表：" + e.getMessage());
            return new LocationRegistry(new LinkedHashMap<>());
        }
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    public static Map<String, LocationEntry> readEntries(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return new LinkedHashMap<>();
            }
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("location_registry.json root must be an object");
            }

            JsonObject object = root.getAsJsonObject();
            JsonObject source = object.has("locations") && object.get("locations").isJsonObject()
                    ? object.getAsJsonObject("locations")
                    : object;

            Map<String, LocationEntry> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank() || key.startsWith("_comment") || "schemaVersion".equals(key)) {
                    continue;
                }
                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull()) {
                    continue;
                }
                if (!value.isJsonObject()) {
                    throw new IllegalArgumentException("location_registry.json entry '" + key + "' must be an object");
                }

                LocationEntry locationEntry = normalizeEntry(GSON.fromJson(value, LocationEntry.class));
                map.put(normalizeName(key), locationEntry);
            }
            return map;
        }
    }

    public static void validateConfig(Path configPath) throws IOException {
        readEntries(configPath);
    }

    private void save() {
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建配置目录失败：" + e.getMessage());
            return;
        }
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            // 构建带注释头的 JsonObject
            JsonObject root = new JsonObject();
            root.addProperty("_comment", "关键位置注册表。格式：\"地点名\" -> {x, y, z, dim, note, horizontalRadius, verticalRadius}");
            root.addProperty("_comment2", "可通过游戏内命令或直接编辑此文件更新。");
            root.addProperty("_comment3", "horizontalRadius / verticalRadius 为该地点各自的命中范围；focusTag 为关联的 FocusTag 名称（可选），detailTags 为逗号分隔的 DetailTag 列表（可选）。");
            locations.forEach((name, entry) -> root.add(name, GSON.toJsonTree(normalizeEntry(entry))));
            GSON.toJson(root, writer);
            StudioWorkspaceManifestFile.markVersionUpdated("locations",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }

    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath))
            return;
        StudioWorkspacePaths.migrateLegacyFileIfMissing(configPath,
                StudioWorkspacePaths.getLegacyConfigPath(CONFIG_FILENAME), LOG_PREFIX);
        if (Files.exists(configPath)) {
            return;
        }
        try {
            Files.createDirectories(configPath.getParent());
            try (InputStream in = LocationRegistry.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    System.err.println(LOG_PREFIX + "未找到内置默认资源：" + DEFAULT_RESOURCE);
                    return;
                }
                Files.copy(in, configPath);
                System.out.println(LOG_PREFIX + "已创建默认配置：" + configPath);
            }
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "复制默认配置失败：" + e.getMessage());
        }
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static LocationEntry normalizeEntry(LocationEntry entry) {
        LocationEntry safeEntry = entry != null ? entry : new LocationEntry();
        safeEntry.dim = safeEntry.dim != null && !safeEntry.dim.isBlank()
                ? safeEntry.dim.trim()
                : "minecraft:overworld";
        safeEntry.note = safeEntry.note != null ? safeEntry.note.trim() : "";
        safeEntry.horizontalRadius = safeEntry.horizontalRadius > 0 ? safeEntry.horizontalRadius : DEFAULT_HORIZONTAL_RADIUS;
        safeEntry.verticalRadius = safeEntry.verticalRadius > 0 ? safeEntry.verticalRadius : DEFAULT_VERTICAL_RADIUS;
        safeEntry.focusTag = safeEntry.focusTag != null ? safeEntry.focusTag.trim() : "";
        safeEntry.detailTags = safeEntry.detailTags != null ? safeEntry.detailTags.trim() : "";
        return safeEntry;
    }
}
