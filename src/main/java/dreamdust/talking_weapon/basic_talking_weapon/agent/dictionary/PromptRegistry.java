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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 提示词注册表：从 config/talking_weapon/prompts.json 加载。
 * 允许用户在不修改 .toml 的情况下深度定制各种触发场景的话术。
 */
public final class PromptRegistry {

    private static final String LOG_PREFIX = "[TalkingWeapon/PromptRegistry] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String CONFIG_FILENAME = "prompts.json";
    private static final String DEFAULT_RESOURCE = "/assets/talking_weapon/default_prompts.json";

    private final Map<String, String> prompts;

    private static PromptRegistry INSTANCE;

    private PromptRegistry(Map<String, String> prompts) {
        this.prompts = prompts;
    }

    public static synchronized PromptRegistry getInstance() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static synchronized void reload() {
        INSTANCE = load();
        System.out.println(LOG_PREFIX + "已完成热重载。");
    }

    public String get(String key, String fallback) {
        return prompts.getOrDefault(key, fallback);
    }

    // ── 快捷获取方法 ──

    public String getRoleBackground() {
        return get("role_background", "你是塞勒瑞思，被封印在剑中的古精灵灵魂。");
    }

    public String getRolePersonality() {
        return get("role_personality", "你有些迷茫，但对冒险充满好奇。");
    }

    public String getSystemCognitionRule() {
        return get("system_cognition_rule", "【核心认知规则】你是一把被玩家携带在身边的有自我意识的武器。在接下来的 [Tagged Context] 中，所有的环境交互、移动、受伤、持有物品等物理状态，都是你的【主人/持有者】在经历。你只是通过感知连接获取到了这些信息，不要错误地认为是自己在进行物理活动。");
    }

    public String getFocusTagDescription() {
        return get("focus_tag_description", "【系统设定 - FocusTag领域说明】\nCOMBAT: 战斗行为或意图\n...");
    }

    public String getEmotionTypeDescription() {
        return get("emotion_type_description", "【系统设定 - 情绪类型说明】\nNEUTRAL: 平静平衡\n...");
    }

    /**
     * per-trigger user message（UserMessage 文本）：描述玩家动作/事件。
     * key: trigger.<source>.user_message
     */
    public String getTriggerUserMessage(String source) {
        return get("trigger." + source + ".user_message", "[OBSERVATION]");
    }

    /**
     * per-trigger event instruction（system 事件指令）。
     * key: trigger.<source>.event_instruction
     */
    public String getTriggerEventInstruction(String source) {
        return get("trigger." + source + ".event_instruction",
                "请根据当前状态，以武器灵魂的身份发表一到两句话的简短评论。");
    }

    public String getTriggerSpeech() {
        return get("trigger_speech", "你感受到一丝灵魂的悸动……");
    }

    public String getCancelSpeech() {
        return get("cancel_speech", "剑：沉默不语……");
    }

    public String getBusySpeech() {
        return get("busy_speech", "剑：我还在思考上一个问题，别催！");
    }

    // ── 加载逻辑 ──

    private static PromptRegistry load() {
        Path configPath = getConfigPath();
        ensureConfigExists(configPath);

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> raw = GSON.fromJson(reader, type);
            Map<String, String> filtered = new HashMap<>();
            if (raw != null) {
                raw.forEach((k, v) -> {
                    if (!k.startsWith("_comment"))
                        filtered.put(k, v);
                });
            }
            return new PromptRegistry(filtered);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "加载失败：" + e.getMessage());
            return new PromptRegistry(Collections.emptyMap());
        }
    }

    public static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(CONFIG_FILENAME);
    }

    private static void ensureConfigExists(Path configPath) {
        if (Files.exists(configPath))
            return;
        try {
            Files.createDirectories(configPath.getParent());
            try (InputStream in = PromptRegistry.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null)
                    return;
                Files.copy(in, configPath);
            }
        } catch (IOException e) {
            System.err.println(LOG_PREFIX + "创建默认配置失败：" + e.getMessage());
        }
    }
}
