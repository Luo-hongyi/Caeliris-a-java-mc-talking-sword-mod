package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceManifestFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 自定义 DetailTag 的显式注册表。
 * <p>
 * 运行时只以 {@code config/talking_weapon/studio_workspace/custom_tags.json} 作为 custom tag 的合法来源；
 * {@code tag_descriptions.json} 仅负责描述文本，不再承担“注册”职责。
 */
public final class CustomTagRegistry {

    private static final String LOG_PREFIX = "[TalkingWeapon/CustomTagRegistry] ";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<CustomTagEntry>>() {
    }.getType();

    private static final String CONFIG_FILENAME = "custom_tags.json";
    private static final String DEFAULT_DESCRIPTION_RESOURCE = "/assets/talking_weapon/default_tag_descriptions.json";

    private static CustomTagRegistry INSTANCE;

    private final LinkedHashMap<String, CustomTagEntry> entries;

    private CustomTagRegistry(List<CustomTagEntry> loadedEntries) {
        this.entries = new LinkedHashMap<>();
        for (CustomTagEntry entry : loadedEntries) {
            this.entries.put(entry.tag, entry.copy());
        }
    }

    public static synchronized CustomTagRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已重新加载，共 " + INSTANCE.entries.size() + " 条 custom tag。");
    }

    public boolean contains(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        DetailTag parsed = DetailTag.parse(tag);
        if (parsed.getSource() != DetailTag.TagSource.CUSTOM) {
            return false;
        }
        return entries.containsKey(parsed.toString());
    }

    public Predicate<String> asLookup() {
        return this::contains;
    }

    public List<CustomTagEntry> getAllEntries() {
        List<CustomTagEntry> copy = new ArrayList<>();
        for (CustomTagEntry entry : entries.values()) {
            copy.add(entry.copy());
        }
        return Collections.unmodifiableList(copy);
    }

    public synchronized void updateAll(Collection<CustomTagEntry> newEntries) {
        List<CustomTagEntry> normalized = normalizeEntries(newEntries);
        entries.clear();
        for (CustomTagEntry entry : normalized) {
            entries.put(entry.tag, entry.copy());
        }
        save();
        System.out.println(LOG_PREFIX + "已更新并保存，共 " + entries.size() + " 条 custom tag。");
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    public static List<CustomTagEntry> readEntries(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return parseEntries(root);
        }
    }

    public static Set<String> toTagSet(Collection<CustomTagEntry> entries) {
        Set<String> result = new LinkedHashSet<>();
        if (entries == null) {
            return result;
        }
        for (CustomTagEntry entry : normalizeEntries(entries)) {
            result.add(entry.tag);
        }
        return result;
    }

    private static CustomTagRegistry load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);
        try {
            List<CustomTagEntry> loadedEntries = readEntries(configPath);
            System.out.println(LOG_PREFIX + "加载完成，共 " + loadedEntries.size() + " 条 custom tag：" + configPath);
            return new CustomTagRegistry(loadedEntries);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空注册表：" + e.getMessage());
            return new CustomTagRegistry(Collections.emptyList());
        }
    }

    private static List<CustomTagEntry> parseEntries(JsonElement root) {
        if (root == null || root.isJsonNull()) {
            return Collections.emptyList();
        }
        if (root.isJsonArray()) {
            List<CustomTagEntry> rawEntries = GSON.fromJson(root, ENTRY_LIST_TYPE);
            return normalizeEntries(rawEntries);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("custom_tags.json root must be an object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement tagsElement = object.get("tags");
        if (tagsElement == null || tagsElement.isJsonNull()) {
            return Collections.emptyList();
        }
        if (!tagsElement.isJsonArray()) {
            throw new IllegalArgumentException("custom_tags.json.tags must be an array");
        }
        List<CustomTagEntry> rawEntries = GSON.fromJson(tagsElement, ENTRY_LIST_TYPE);
        return normalizeEntries(rawEntries);
    }

    private static List<CustomTagEntry> normalizeEntries(Collection<CustomTagEntry> entries) {
        List<CustomTagEntry> normalized = new ArrayList<>();
        if (entries == null) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (CustomTagEntry rawEntry : entries) {
            if (rawEntry == null || rawEntry.tag == null || rawEntry.tag.isBlank()) {
                continue;
            }
            DetailTag parsed = DetailTag.parse(rawEntry.tag);
            if (parsed.getSource() != DetailTag.TagSource.CUSTOM) {
                throw new IllegalArgumentException("Only custom DetailTag can be stored in custom_tags.json: " + rawEntry.tag);
            }
            String normalizedTag = parsed.toString();
            String normalizedKind = rawEntry.kind == null || rawEntry.kind.isBlank()
                    ? parsed.getPrefix()
                    : rawEntry.kind.trim();
            if (!parsed.getPrefix().equals(normalizedKind)) {
                throw new IllegalArgumentException("custom tag kind does not match tag prefix: " + normalizedTag);
            }
            if (!seen.add(normalizedTag)) {
                throw new IllegalArgumentException("Duplicate custom tag: " + normalizedTag);
            }

            CustomTagEntry entry = new CustomTagEntry();
            entry.tag = normalizedTag;
            entry.kind = normalizedKind;
            entry.label = rawEntry.label != null ? rawEntry.label : "";
            entry.aliases = rawEntry.aliases != null ? new ArrayList<>(rawEntry.aliases) : new ArrayList<>();
            entry.notes = rawEntry.notes != null ? rawEntry.notes : "";
            entry.group = rawEntry.group != null ? rawEntry.group : "";
            normalized.add(entry);
        }
        return normalized;
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
            CustomTagFile file = new CustomTagFile();
            file.tags = buildBundledDefaultEntries();
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
            System.out.println(LOG_PREFIX + "已创建默认配置：" + configPath);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建默认配置失败：" + e.getMessage());
        }
    }

    private static List<CustomTagEntry> buildBundledDefaultEntries() {
        LinkedHashMap<String, CustomTagEntry> bundled = new LinkedHashMap<>();
        try (InputStream in = CustomTagRegistry.class.getResourceAsStream(DEFAULT_DESCRIPTION_RESOURCE)) {
            if (in != null) {
                try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                        String tag = entry.getKey();
                        if (!tag.startsWith(DetailTag.CUSTOM_GENERIC + ":")
                                && !tag.startsWith(DetailTag.CUSTOM_SPECIFIC + ":")) {
                            continue;
                        }
                        DetailTag parsed = DetailTag.parse(tag);
                        CustomTagEntry customTag = new CustomTagEntry();
                        customTag.tag = parsed.toString();
                        customTag.kind = parsed.getPrefix();
                        customTag.label = extractDescription(entry.getValue(), parsed.getValue());
                        customTag.aliases = new ArrayList<>();
                        customTag.notes = "";
                        customTag.group = "";
                        bundled.put(customTag.tag, customTag);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "读取内置 default_tag_descriptions 失败，将退回最小默认集：" + e.getMessage());
        }

        bundled.computeIfAbsent("custom_specific:亚尔夫海姆", key -> {
            CustomTagEntry entry = new CustomTagEntry();
            entry.tag = key;
            entry.kind = DetailTag.CUSTOM_SPECIFIC;
            entry.label = "亚尔夫海姆";
            entry.aliases = new ArrayList<>();
            entry.notes = "";
            entry.group = "landmark";
            return entry;
        });
        return new ArrayList<>(bundled.values());
    }

    private static String extractDescription(JsonElement value, String fallback) {
        if (value != null && value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement description = object.get("description");
            if (description != null && description.isJsonPrimitive()) {
                return description.getAsString();
            }
        }
        return fallback != null ? fallback : "";
    }

    private void save() {
        Path configPath = getConfigPath();
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            CustomTagFile file = new CustomTagFile();
            file.tags = getAllEntries();
            GSON.toJson(file, writer);
            StudioWorkspaceManifestFile.markVersionUpdated("customTags",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }

    private static final class CustomTagFile {
        public int schemaVersion = 1;
        public List<CustomTagEntry> tags = new ArrayList<>();
    }

    public static final class CustomTagEntry {
        public String tag;
        public String kind;
        public String label;
        public List<String> aliases = new ArrayList<>();
        public String notes;
        public String group;

        public CustomTagEntry copy() {
            CustomTagEntry copy = new CustomTagEntry();
            copy.tag = tag;
            copy.kind = kind;
            copy.label = label;
            copy.aliases = aliases != null ? new ArrayList<>(aliases) : new ArrayList<>();
            copy.notes = notes;
            copy.group = group;
            return copy;
        }
    }
}
