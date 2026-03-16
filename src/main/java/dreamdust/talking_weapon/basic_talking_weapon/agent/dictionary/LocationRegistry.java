package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 关键位置注册表：保存玩家与 Agent 共同维护的命名地标。
 * <p>
 * 从 {@code config/talking_weapon/location_registry.json} 加载，
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

    private static final String CONFIG_SUBDIR = "talking_weapon";
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
        }

        public LocationEntry(int x, int y, int z, String dim, String note, String focusTag, String detailTags) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
            this.note = note;
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

    // ─────────────────────────────────────────────
    // 运行时写入（供未来命令系统调用）
    // ─────────────────────────────────────────────

    /**
     * 新增或更新一个地点，并立即持久化到 JSON。
     */
    public synchronized void put(String name, LocationEntry entry) {
        locations.put(name.toLowerCase(Locale.ROOT), entry);
        save();
        System.out.println(LOG_PREFIX + "更新地点 '" + name + "' → " + entry.toPromptString());
    }

    /**
     * 删除一个地点，并立即持久化到 JSON。
     *
     * @return true 若该地点存在且已删除
     */
    public synchronized boolean remove(String name) {
        boolean existed = locations.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (existed) {
            save();
        }
        return existed;
    }

    // ─────────────────────────────────────────────
    // 加载 / 保存
    // ─────────────────────────────────────────────

    private static LocationRegistry load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Map<String, LocationEntry> map = new LinkedHashMap<>();

            root.entrySet().forEach(entry -> {
                if (!entry.getKey().startsWith("_comment")) {
                    try {
                        LocationEntry loc = GSON.fromJson(entry.getValue(), LocationEntry.class);
                        map.put(entry.getKey().toLowerCase(Locale.ROOT), loc);
                    } catch (Exception e) {
                        System.err.println(LOG_PREFIX + "跳过无法解析的条目 '" + entry.getKey() + "'：" + e.getMessage());
                    }
                }
            });

            System.out.println(LOG_PREFIX + "加载完成，共 " + map.size() + " 个地点：" + configPath);
            return new LocationRegistry(map);

        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空注册表：" + e.getMessage());
            return new LocationRegistry(new LinkedHashMap<>());
        }
    }

    private void save() {
        Path configPath = getConfigPath();
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            // 构建带注释头的 JsonObject
            JsonObject root = new JsonObject();
            root.addProperty("_comment", "关键位置注册表。格式：\"地点名\" -> {x, y, z, dim, note}");
            root.addProperty("_comment2", "可通过游戏内命令或直接编辑此文件更新。");
            locations.forEach((name, entry) -> root.add(name, GSON.toJsonTree(entry)));
            GSON.toJson(root, writer);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(CONFIG_FILENAME);
    }

    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath))
            return;
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
}
