package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceManifestFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 玩家身份登记表。
 * <p>
 * 以稳定 playerId 为主键，维护当前默认显示名与可选别名。
 * 当前主要用于构造 {@link TaggedContextSource} 的 player source，
 * 避免各 collector 自己散落拼接 UUID 与显示名。
 */
public final class PlayerIdentityRegistry {

    private static final String LOG_PREFIX = "[TalkingWeapon/PlayerIdentityRegistry] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String CONFIG_FILENAME = "player_identity_registry.json";
    private static final String DEFAULT_RESOURCE = "/assets/talking_weapon/default_player_identity_registry.json";

    private static PlayerIdentityRegistry INSTANCE;

    public static final class PlayerIdentityEntry {
        public String displayName = "";
        public List<String> aliases = new ArrayList<>();
        public String note = "";
    }

    private final Map<String, PlayerIdentityEntry> identities;

    private PlayerIdentityRegistry(Map<String, PlayerIdentityEntry> identities) {
        this.identities = identities;
    }

    public static synchronized PlayerIdentityRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已重新加载，共 " + INSTANCE.identities.size() + " 个玩家身份。");
    }

    public synchronized TaggedContextSource resolveSource(Player player) {
        if (player == null) {
            return TaggedContextSource.player("unknown_player", "unknown_player");
        }
        return resolveSource(player.getUUID().toString(), player.getName().getString());
    }

    public synchronized TaggedContextSource resolveSource(String playerId, String observedDisplayName) {
        String normalizedId = normalizePlayerId(playerId);
        String normalizedObservedName = normalizeDisplayName(observedDisplayName, normalizedId);

        PlayerIdentityEntry entry = identities.get(normalizedId);
        boolean changed = false;
        if (entry == null) {
            entry = new PlayerIdentityEntry();
            entry.displayName = normalizedObservedName;
            identities.put(normalizedId, entry);
            changed = true;
        } else {
            changed = refreshObservedName(entry, normalizedObservedName);
        }

        normalizeEntry(entry);
        if (changed) {
            save();
        }
        return TaggedContextSource.player(normalizedId, entry.displayName);
    }

    public synchronized PlayerIdentityEntry get(String playerId) {
        String normalizedId = normalizePlayerId(playerId);
        PlayerIdentityEntry entry = identities.get(normalizedId);
        if (entry == null) {
            return null;
        }
        PlayerIdentityEntry copy = new PlayerIdentityEntry();
        copy.displayName = entry.displayName;
        copy.aliases = new ArrayList<>(entry.aliases);
        copy.note = entry.note;
        return copy;
    }

    private boolean refreshObservedName(PlayerIdentityEntry entry, String observedDisplayName) {
        normalizeEntry(entry);
        if (observedDisplayName.isBlank() || observedDisplayName.equals(entry.displayName)) {
            return false;
        }
        if (!entry.displayName.isBlank()) {
            entry.aliases.add(entry.displayName);
        }
        entry.displayName = observedDisplayName;
        normalizeEntry(entry);
        return true;
    }

    private static PlayerIdentityRegistry load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try {
            Map<String, PlayerIdentityEntry> map = readEntries(configPath);
            System.out.println(LOG_PREFIX + "加载完成，共 " + map.size() + " 个玩家身份：" + configPath);
            return new PlayerIdentityRegistry(map);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败，使用空注册表：" + e.getMessage());
            return new PlayerIdentityRegistry(new LinkedHashMap<>());
        }
    }

    public static Path getConfigPath() {
        return StudioWorkspacePaths.getAuthoredFilePath(CONFIG_FILENAME);
    }

    public static Map<String, PlayerIdentityEntry> readEntries(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || root.isJsonNull()) {
                return new LinkedHashMap<>();
            }
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("player_identity_registry.json root must be an object");
            }

            JsonObject object = root.getAsJsonObject();
            JsonObject source = object.has("players") && object.get("players").isJsonObject()
                    ? object.getAsJsonObject("players")
                    : object;

            Map<String, PlayerIdentityEntry> map = new LinkedHashMap<>();
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
                    throw new IllegalArgumentException(
                            "player_identity_registry.json entry '" + key + "' must be an object");
                }
                map.put(normalizePlayerId(key), normalizeEntry(GSON.fromJson(value, PlayerIdentityEntry.class)));
            }
            return map;
        }
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
            JsonObject root = new JsonObject();
            root.addProperty("_comment", "玩家身份登记表。key 为稳定 player UUID。");
            root.addProperty("_comment2", "displayName 为默认显示名；aliases 与 note 可手动维护。");
            JsonObject players = new JsonObject();
            identities.forEach((playerId, entry) -> players.add(playerId, GSON.toJsonTree(normalizeEntry(entry))));
            root.add("players", players);
            GSON.toJson(root, writer);
            StudioWorkspaceManifestFile.markVersionUpdated("playerIdentities",
                    StudioWorkspaceManifestFile.UPDATED_BY_MINECRAFT);
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "保存失败：" + e.getMessage());
        }
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
            try (InputStream in = PlayerIdentityRegistry.class.getResourceAsStream(DEFAULT_RESOURCE)) {
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

    private static String normalizePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return "unknown_player";
        }
        return playerId.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isBlank()) {
            return fallback;
        }
        return displayName.trim();
    }

    private static PlayerIdentityEntry normalizeEntry(PlayerIdentityEntry entry) {
        PlayerIdentityEntry safeEntry = entry != null ? entry : new PlayerIdentityEntry();
        safeEntry.displayName = safeEntry.displayName != null ? safeEntry.displayName.trim() : "";
        safeEntry.note = safeEntry.note != null ? safeEntry.note.trim() : "";
        if (safeEntry.aliases == null) {
            safeEntry.aliases = new ArrayList<>();
        }
        LinkedHashSet<String> normalizedAliases = new LinkedHashSet<>();
        for (String alias : safeEntry.aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            normalizedAliases.add(alias.trim());
        }
        safeEntry.aliases = new ArrayList<>(normalizedAliases);
        return safeEntry;
    }
}
