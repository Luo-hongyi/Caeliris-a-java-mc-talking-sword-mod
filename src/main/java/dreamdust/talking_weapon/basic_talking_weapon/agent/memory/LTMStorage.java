package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LTMStorage：处理长期记忆的本地化存储。
 * <p>
 * 记忆分两级存储：
 * <ul>
 *   <li><b>全局配置（Global Implanted LTMs）</b>：存放在 {@code config/talking_weapon/implanted_memories.json}
 *       这些记忆跨地图通用，视为人设和出厂设定的补充，带有 SourceType.IMPLANTED 属性。</li>
 *   <li><b>存档级配置（World Synthesized LTMs）</b>：<i>（即将开发）</i>将保存在 world 文件夹下。</li>
 * </ul>
 */
public final class LTMStorage {

    private static final String LOG_PREFIX = "[TalkingWeapon/LTMStorage] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String GLOBAL_IMPLANTED_FILE = "implanted_memories.json";

    /**
     * 从全局配置目录加载所有预先植入的记忆（IMPLANTED）。
     * <p>
     * 在 AgentRuntime 初始化时由 AgentMemory 调用。
     */
    public static List<LTMEntry> loadGlobalImplantedMemories() {
        Path configPath = getGlobalConfigPath();
        ensureConfigExists(configPath);

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<ImplantedJsonEntry>>() {}.getType();
            List<ImplantedJsonEntry> rawList = GSON.fromJson(reader, type);
            
            List<LTMEntry> result = new ArrayList<>();
            if (rawList != null) {
                for (ImplantedJsonEntry raw : rawList) {
                    boolean validEntry = true;
                    // 转译
                    LTMEntry.Builder builder = LTMEntry.builder()
                        .sourceType(LTMEntry.SourceType.IMPLANTED)
                        .description(raw.description != null ? raw.description : "")
                        .importance(raw.importance > 0 ? raw.importance : 1.0f);

                    if (raw.id != null && !raw.id.isBlank()) {
                        try {
                            builder.id(UUID.fromString(raw.id));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (raw.occurred_game_time >= 0L) {
                        builder.occurredGameTime(raw.occurred_game_time);
                    }
                    if (raw.time_label != null && !raw.time_label.isBlank()) {
                        builder.semanticTimeLabel(raw.time_label);
                    }
                    if (raw.dimension_id != null && !raw.dimension_id.isBlank()) {
                        builder.dimensionId(raw.dimension_id);
                    }
                    if (raw.biome_id != null && !raw.biome_id.isBlank()) {
                        builder.biomeId(raw.biome_id);
                    }
                    if (raw.raining != null) {
                        builder.raining(raw.raining);
                    }
                    
                    if (raw.focus_tags != null) {
                        for (String fStr : raw.focus_tags) {
                            try {
                                builder.focus(FocusTag.valueOf(fStr.toUpperCase()));
                            } catch (Exception ignored) {}
                        }
                    }
                    if (raw.detail_tags != null) {
                        for (String dStr : raw.detail_tags) {
                            try {
                                builder.detail(TagPolicy.parseRegisteredTag(dStr));
                            } catch (IllegalArgumentException e) {
                                validEntry = false;
                                System.err.println(LOG_PREFIX + "植入记忆存在未登记或非法 DetailTag，已跳过该条记忆: " + dStr);
                                break;
                            }
                        }
                    }
                    if (validEntry) {
                        result.add(builder.build());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "植入记忆加载失败：" + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 向全局配置文件中追加一条 IMPLANTED 记忆。
     * 可供后续的 GUI 编辑器/指令直接调用保存。
     */
    public static void saveGlobalImplantedMemory(LTMEntry entry) {
        if (entry.getSourceType() != LTMEntry.SourceType.IMPLANTED) {
            System.err.println(LOG_PREFIX + "拒绝保存: 只能保存 IMPLANTED 以作为出厂属性。");
            return;
        }

        Path configPath = getGlobalConfigPath();
        ensureConfigExists(configPath);

        List<ImplantedJsonEntry> list;
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<ImplantedJsonEntry>>() {}.getType();
            list = GSON.fromJson(reader, type);
            if (list == null) list = new ArrayList<>();
        } catch (Exception e) {
            list = new ArrayList<>();
        }

        // 构造序列化对象
        ImplantedJsonEntry newJson = new ImplantedJsonEntry();
        newJson.id = entry.getId().toString();
        newJson.description = entry.getDescription();
        newJson.importance = entry.getImportance();
        newJson.occurred_game_time = entry.getOccurredGameTime();
        newJson.time_label = entry.getSemanticTimeLabel();
        newJson.dimension_id = entry.getDimensionId();
        newJson.biome_id = entry.getBiomeId();
        newJson.raining = entry.getRaining();
        newJson.focus_tags = new ArrayList<>();
        for (FocusTag f : entry.getFocusTags()) newJson.focus_tags.add(f.name());
        newJson.detail_tags = new ArrayList<>();
        for (var d : entry.getDetailTags()) {
            TagPolicy.requireRegisteredCustomTag(d);
            newJson.detail_tags.add(d.toString());
        }
        
        list.add(newJson);

        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            GSON.toJson(list, writer);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "写入记忆失败：" + e.getMessage());
        }
    }

    private static Path getGlobalConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(GLOBAL_IMPLANTED_FILE);
    }

    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath)) return;
        try {
            Files.createDirectories(configPath.getParent());
            // 写入一个带说明的默认模板数组
            List<ImplantedJsonEntry> defaultSet = new ArrayList<>();
            ImplantedJsonEntry template = new ImplantedJsonEntry();
            template.description = "我最初被锻造于亚尔夫海姆的最北端。那是一片冰冷但充满魔力的土地。";
            template.focus_tags = List.of("LANDSCAPE", "CREATION");
            template.detail_tags = List.of("custom_specific:亚尔夫海姆");
            template.importance = 1.0f;
            defaultSet.add(template);

            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(defaultSet, writer);
            }
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建默认植入记忆配置失败：" + e.getMessage());
        }
    }

    /** 专用用于反序列化的中间结构体 */
    private static class ImplantedJsonEntry {
        public String id;
        public String description;
        public List<String> focus_tags;
        public List<String> detail_tags;
        public float importance = 1.0f;
        public long occurred_game_time = -1L;
        public String time_label;
        public String dimension_id;
        public String biome_id;
        public Boolean raining;
    }
}
