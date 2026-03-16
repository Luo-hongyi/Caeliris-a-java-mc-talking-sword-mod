package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * DetailTag 到自然语言描述的映射字典。
 * <p>
 * 从 {@code config/talking_weapon/tag_descriptions.json} 加载，
 * 首次运行时自动从 jar 内资源复制默认配置。
 * <p>
 * <b>用途</b>：在组装 LLM Prompt 时，将机器可读的标签（如 {@code custom_specific:health_critical}）
 * 转为自然语言描述（如"持有者生命危急"），提升 LLM 的理解质量。
 * <p>
 * <b>线程安全</b>：字典在模组初始化时加载一次，此后只读，无需同步。
 */
public final class TagDescriptionDictionary {

    private static final String LOG_PREFIX = "[TalkingWeapon/TagDict] ";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /** 配置文件相对于 Forge config 目录的路径 */
    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String CONFIG_FILENAME = "tag_descriptions.json";

    /** jar 内默认资源路径 */
    private static final String DEFAULT_RESOURCE = "/assets/talking_weapon/default_tag_descriptions.json";

    /** 标签 -> 标签描述 */
    private Map<String, TagDefinition> definitions;

    // ─────────────────────────────────────────────
    // 单例
    // ─────────────────────────────────────────────

    private static TagDescriptionDictionary INSTANCE;

    private TagDescriptionDictionary(Map<String, TagDefinition> definitions) {
        this.definitions = new LinkedHashMap<>(definitions);
    }

    /**
     * 获取单例。首次调用时触发加载。
     */
    public static synchronized TagDescriptionDictionary getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    /**
     * 强制重新加载配置文件（支持热重载）。
     */
    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已重新加载，共 " + INSTANCE.definitions.size() + " 条映射。");
    }

    // ─────────────────────────────────────────────
    // 查询 API
    // ─────────────────────────────────────────────

    /**
     * 查询标签的自然语言描述。
     *
     * @param tag 完整标签字符串，如 {@code "custom_specific:health_critical"}
     * @return 描述文字；若不在字典中则返回 {@code null}
     */
    public String describe(String tag) {
        TagDefinition def = definitions.get(tag);
        if (def != null && def.getDescription() != null && !def.getDescription().isBlank()) {
            return def.getDescription();
        }
        return null;
    }

    /**
     * 查询描述，若不在字典中返回标签原文。
     */
    public String describeOrRaw(String tag) {
        String desc = describe(tag);
        return desc != null ? desc : tag;
    }

    /**
     * 将一组标签转换为自然语言列表，过滤掉没有映射的标签。
     *
     * @param tags 标签集合（DetailTag.toString() 格式）
     * @return 自然语言描述列表（顺序不保证）
     */
    public List<String> describeAll(Collection<String> tags) {
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            String desc = describe(tag);
            if (desc != null)
                result.add(desc);
        }
        return result;
    }

    /** 返回字典中所有标签数量 */
    public int size() {
        return definitions.size();
    }

    public boolean contains(String tag) {
        return definitions.containsKey(tag);
    }

    /** 获取所有条目的只读副本，供外部界面读取 */
    public Map<String, TagDefinition> getAllEntries() {
        return Collections.unmodifiableMap(definitions);
    }

    /**
     * 整体替换字典内容并立即持久化到 JSON。
     *
     * @param newDefinitions 新的标签定义映射
     */
    public synchronized void updateAll(Map<String, TagDefinition> newDefinitions) {
        definitions = new LinkedHashMap<>(newDefinitions);
        save();
        System.out.println(LOG_PREFIX + "已更新并保存，共 " + definitions.size() + " 条映射。");
    }

    // ─────────────────────────────────────────────
    // 加载逻辑
    // ─────────────────────────────────────────────

    private static TagDescriptionDictionary load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, TagDefinition>>() {
            }.getType();
            Map<String, TagDefinition> raw = GSON.fromJson(reader, type);

            // 过滤掉以 "_comment" 开头的注释键
            Map<String, TagDefinition> filtered = new LinkedHashMap<>();
            if (raw != null) {
                raw.forEach((k, v) -> {
                    if (!k.startsWith("_comment")) {
                        filtered.put(k, v);
                    }
                });
            }

            System.out.println(LOG_PREFIX + "加载完成，共 " + filtered.size() + " 条映射：" + configPath);
            return new TagDescriptionDictionary(filtered);

        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空字典：" + e.getMessage());
            return new TagDescriptionDictionary(Collections.emptyMap());
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(CONFIG_FILENAME);
    }

    /**
     * 若配置文件不存在，从 jar 内资源复制默认文件。
     */
    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath))
            return;
        try {
            Files.createDirectories(configPath.getParent());
            try (InputStream in = TagDescriptionDictionary.class.getResourceAsStream(DEFAULT_RESOURCE)) {
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

    /**
     * 将当前字典写回配置文件。
     */
    private void save() {
        Path configPath = getConfigPath();
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            // 构建带注释头的 JsonObject
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.addProperty("_comment", "DetailTag 到自然语言的描述映射。当 Collector 产出某个 DetailTag 时，将用此处的描述注入 LLM 提示词。");
            root.addProperty("_comment2", "此文件只维护描述文本。标签之间的一对多语义关系请写入 tag_relations.json。");
            Gson serializer = new GsonBuilder().setPrettyPrinting().create();
            definitions.forEach((key, def) -> root.add(key, serializer.toJsonTree(def)));
            serializer.toJson(root, writer);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }

}
