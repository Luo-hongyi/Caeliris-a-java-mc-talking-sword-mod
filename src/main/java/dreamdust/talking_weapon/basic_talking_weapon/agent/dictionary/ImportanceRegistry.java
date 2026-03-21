package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 重要度注册表：从 config/talking_weapon/importance.json 加载。
 * 允许用户深入定制 Agent 对各项游戏环境和动作的基准注意力权重 (BaseImportance)。
 */
public final class ImportanceRegistry {

    private static final String LOG_PREFIX = "[TalkingWeapon/ImportanceRegistry] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String CONFIG_FILENAME = "importance.json";
    private static final String DEFAULT_RESOURCE = "/assets/talking_weapon/default_importance.json";

    private final Map<String, Float> importanceMap;

    private static ImportanceRegistry INSTANCE;

    private ImportanceRegistry(Map<String, Float> importanceMap) {
        this.importanceMap = importanceMap;
    }

    public static synchronized ImportanceRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已完成热重载。");
    }

    /**
     * 获取指定环境或动作类别的基础客观重要度。
     * @param key json配置里的key
     * @param fallback 若未配置则使用默认值
     */
    public float get(String key, float fallback) {
        return importanceMap.getOrDefault(key, fallback);
    }

    // ── 加载逻辑 ──

    private static ImportanceRegistry load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try {
            return new ImportanceRegistry(readEntries(configPath));
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败：" + e.getMessage());
            return new ImportanceRegistry(Collections.emptyMap());
        }
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    public static Map<String, Float> readEntries(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, Float>>() {
            }.getType();
            Map<String, Float> raw = GSON.fromJson(reader, type);
            Map<String, Float> filtered = new HashMap<>();
            if (raw != null) {
                raw.forEach((k, v) -> {
                    if (!k.startsWith("_comment")) {
                        filtered.put(k, v);
                    }
                });
            }
            return filtered;
        }
    }

    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath))
            return;
        try {
            StudioWorkspacePaths.migrateLegacyFileIfMissing(configPath,
                    StudioWorkspacePaths.getLegacyConfigPath(CONFIG_FILENAME), LOG_PREFIX);
            if (Files.exists(configPath)) {
                return;
            }
            Files.createDirectories(configPath.getParent());
            try (InputStream in = ImportanceRegistry.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null)
                    return;
                Files.copy(in, configPath);
            }
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建默认配置失败：" + e.getMessage());
        }
    }
}
