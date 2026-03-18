package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.CustomTagRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceManifestFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;

import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * personas.json 的运行时读写与校验。
 * <p>
 * 该文件只负责静态人格条目，不包含 favorability / playerProfile 等运行时状态。
 */
public final class PersonaStorage {

    private static final String LOG_PREFIX = "[TalkingWeapon/PersonaStorage] ";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Type PERSONA_LIST_TYPE = new TypeToken<List<PersonaEntry>>() {
    }.getType();

    private static final String CONFIG_FILENAME = "personas.json";

    private PersonaStorage() {
    }

    public static List<PersonaEntry> loadRuntimePersonas() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);
        try {
            List<PersonaEntry> entries = readEntries(configPath, CustomTagRegistry.getInstance().asLookup());
            System.out.println(LOG_PREFIX + "加载完成，共 " + entries.size() + " 条 persona：" + configPath);
            return entries;
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空列表：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static List<PersonaEntry> readEntries(Path configPath, Predicate<String> customTagLookup) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            return parseEntries(root, customTagLookup);
        }
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    public static void saveEntries(Collection<PersonaEntry> entries) {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            PersonaFile file = new PersonaFile();
            file.personas = normalizeEntries(entries, CustomTagRegistry.getInstance().asLookup());
            GSON.toJson(file, writer);
            StudioWorkspaceManifestFile.markVersionUpdated("personas",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
    }

    private static List<PersonaEntry> parseEntries(JsonElement root, Predicate<String> customTagLookup) {
        if (root == null || root.isJsonNull()) {
            return new ArrayList<>();
        }
        if (root.isJsonArray()) {
            List<PersonaEntry> rawEntries = GSON.fromJson(root, PERSONA_LIST_TYPE);
            return normalizeEntries(rawEntries, customTagLookup);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("personas.json root must be an object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement personasElement = object.get("personas");
        if (personasElement == null || personasElement.isJsonNull()) {
            return new ArrayList<>();
        }
        if (!personasElement.isJsonArray()) {
            throw new IllegalArgumentException("personas.json.personas must be an array");
        }
        List<PersonaEntry> rawEntries = GSON.fromJson(personasElement, PERSONA_LIST_TYPE);
        return normalizeEntries(rawEntries, customTagLookup);
    }

    private static List<PersonaEntry> normalizeEntries(Collection<PersonaEntry> entries, Predicate<String> customTagLookup) {
        if (entries == null) {
            return new ArrayList<>();
        }
        Map<String, PersonaEntry> normalized = new LinkedHashMap<>();
        int generatedIndex = 0;
        for (PersonaEntry rawEntry : entries) {
            if (rawEntry == null) {
                continue;
            }
            String description = rawEntry.getDescription() != null ? rawEntry.getDescription().trim() : "";
            if (description.isEmpty()) {
                continue;
            }
            FocusTag focus = rawEntry.getAssociatedFocus();
            if (focus == null) {
                throw new IllegalArgumentException("Persona entry missing associated_focus: " + description);
            }

            PersonaEntry entry = new PersonaEntry();
            String id = rawEntry.getId();
            if (id == null || id.isBlank()) {
                generatedIndex++;
                id = "persona_" + generatedIndex;
            }
            entry.setId(id);
            entry.setEnabled(rawEntry.isEnabled());
            entry.setDescription(description);
            entry.setAssociatedFocus(focus);
            entry.setAlwaysRecalled(rawEntry.isAlwaysRecalled());
            entry.setNotes(rawEntry.getNotes());
            entry.setDetailTags(rawEntry.getDetailTags());

            for (String detailTag : entry.getDetailTags()) {
                TagPolicy.parseRegisteredTag(detailTag, customTagLookup);
            }
            if (normalized.putIfAbsent(entry.getId(), entry) != null) {
                throw new IllegalArgumentException("Duplicate persona id: " + entry.getId());
            }
        }
        return new ArrayList<>(normalized.values());
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
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(new PersonaFile(), writer);
            }
            System.out.println(LOG_PREFIX + "已创建默认配置：" + configPath);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建默认配置失败：" + e.getMessage());
        }
    }

    private static final class PersonaFile {
        public int schemaVersion = 1;
        public List<PersonaEntry> personas = Collections.emptyList();
    }
}
