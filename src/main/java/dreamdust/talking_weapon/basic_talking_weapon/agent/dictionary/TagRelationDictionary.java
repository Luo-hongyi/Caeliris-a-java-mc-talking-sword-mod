package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceManifestFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * DetailTag 关系图：维护一对多、无环的语义投影关系。
 * <p>
 * 该图只参与程序侧的匹配 / fallback，不直接决定 Prompt 输出。
 */
public final class TagRelationDictionary {

    private static final String LOG_PREFIX = "[TalkingWeapon/TagRelation] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String CONFIG_FILENAME = "tag_relations.json";
    private static final String DEFAULT_RESOURCE = "/assets/talking_weapon/default_tag_relations.json";

    private static TagRelationDictionary INSTANCE;

    private final Map<String, List<String>> edges;

    private TagRelationDictionary(Map<String, List<String>> edges) {
        this.edges = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : edges.entrySet()) {
            this.edges.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    public static synchronized TagRelationDictionary getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已重新加载，共 " + INSTANCE.edges.size() + " 条关系起点。");
    }

    public List<String> getDirectTargets(String tag) {
        return edges.getOrDefault(tag, Collections.emptyList());
    }

    /**
     * 返回某个标签的可达闭包，默认包含自身。
     */
    public Set<String> expand(String tag) {
        if (tag == null || tag.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        result.add(tag);
        stack.push(tag);

        while (!stack.isEmpty()) {
            String current = stack.pop();
            for (String next : getDirectTargets(current)) {
                if (result.add(next)) {
                    stack.push(next);
                }
            }
        }
        return result;
    }

    public Set<String> expandAll(Collection<String> tags) {
        Set<String> result = new LinkedHashSet<>();
        if (tags == null) {
            return result;
        }
        for (String tag : tags) {
            result.addAll(expand(tag));
        }
        return result;
    }

    public Map<String, List<String>> getAllEntries() {
        return Collections.unmodifiableMap(edges);
    }

    public synchronized void updateAll(Map<String, List<String>> newEdges) {
        validate(newEdges);
        edges.clear();
        for (Map.Entry<String, List<String>> entry : newEdges.entrySet()) {
            edges.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        save();
        System.out.println(LOG_PREFIX + "已更新并保存，共 " + edges.size() + " 条关系起点。");
    }

    private static TagRelationDictionary load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try {
            Map<String, List<String>> filtered = readEdges(configPath, CustomTagRegistry.getInstance().asLookup());
            System.out.println(LOG_PREFIX + "加载完成，共 " + filtered.size() + " 条关系起点：" + configPath);
            return new TagRelationDictionary(filtered);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空关系图：" + e.getMessage());
            return new TagRelationDictionary(Collections.emptyMap());
        }
    }

    private static void validate(Map<String, List<String>> graph) {
        validate(graph, CustomTagRegistry.getInstance().asLookup());
    }

    public static Map<String, List<String>> readEdges(Path configPath, Predicate<String> customTagLookup) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return Collections.emptyMap();
            }
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("tag_relations.json root must be an object");
            }

            JsonObject object = root.getAsJsonObject();
            JsonElement relationsElement = object.get("relations");
            Map<String, List<String>> filtered = new LinkedHashMap<>();
            JsonObject source = relationsElement != null && relationsElement.isJsonObject()
                    ? relationsElement.getAsJsonObject()
                    : object;

            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("_comment") || "schemaVersion".equals(key)) {
                    continue;
                }

                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull()) {
                    filtered.put(key, Collections.emptyList());
                    continue;
                }

                if (!value.isJsonArray()) {
                    throw new IllegalArgumentException(
                            "tag_relations.json entry '" + key + "' must be an array of strings");
                }

                List<String> targets = new ArrayList<>();
                value.getAsJsonArray().forEach(item -> {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException(
                                "tag_relations.json entry '" + key + "' contains a non-string target");
                    }
                    targets.add(item.getAsString());
                });
                filtered.put(key, targets);
            }

            validate(filtered, customTagLookup);
            return filtered;
        }
    }

    private static void validate(Map<String, List<String>> graph, Predicate<String> customTagLookup) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : graph.entrySet()) {
            DetailTag source = DetailTag.parse(entry.getKey());
            TagPolicy.requireRegisteredCustomTag(source, customTagLookup);

            List<String> targets = new ArrayList<>();
            for (String rawTarget : entry.getValue()) {
                DetailTag target = DetailTag.parse(rawTarget);
                TagPolicy.requireRegisteredCustomTag(target, customTagLookup);
                targets.add(target.toString());
            }
            normalized.put(source.toString(), targets);
        }

        detectCycles(normalized);
    }

    private static void detectCycles(Map<String, List<String>> graph) {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();

        for (String node : graph.keySet()) {
            dfsCycle(node, graph, visiting, visited);
        }
    }

    private static void dfsCycle(String node, Map<String, List<String>> graph,
            Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new IllegalArgumentException("Tag relation graph contains a cycle at " + node);
        }
        for (String next : graph.getOrDefault(node, Collections.emptyList())) {
            dfsCycle(next, graph, visiting, visited);
        }
        visiting.remove(node);
        visited.add(node);
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath)) {
            return;
        }
        StudioWorkspacePaths.migrateLegacyFileIfMissing(configPath,
                StudioWorkspacePaths.getLegacyConfigPath(CONFIG_FILENAME), LOG_PREFIX);
        if (Files.exists(configPath)) {
            return;
        }
        try {
            Files.createDirectories(configPath.getParent());
            try (InputStream in = TagRelationDictionary.class.getResourceAsStream(DEFAULT_RESOURCE)) {
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

    private void save() {
        Path configPath = getConfigPath();
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.addProperty("_comment", "DetailTag 的一对多语义关系图。只用于程序侧匹配/回退，不直接决定 Prompt 输出。");
            root.addProperty("_comment2", "图必须无环。允许 native_* 与 custom_* 作为起点；custom_* 若被引用，必须先在 custom_tags.json 中登记。");
            edges.forEach((key, value) -> root.add(key, GSON.toJsonTree(value)));
            GSON.toJson(root, writer);
            StudioWorkspaceManifestFile.markVersionUpdated("relations",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }
}
