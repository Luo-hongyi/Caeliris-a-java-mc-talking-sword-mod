package dreamdust.talking_weapon.basic_talking_weapon.config;

import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioMemoryRecallTuningFile;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局配置：基于 Forge Config 系统，对应 talking_weapon-client.toml。
 */
public final class TalkingWeaponConfig {

        private static final String DEFAULT_WEAPON_NAME = "塞勒瑞斯";
        private static volatile String resolvedWeaponName = DEFAULT_WEAPON_NAME;
        private static volatile boolean syncingRecallParametersFromWorkspace = false;

        // ═══════════════════════════════════════════════════════════════
        // 厂商 / 模型预设
        // ═══════════════════════════════════════════════════════════════

        public static final List<String> AVAILABLE_PROVIDERS = List.of(
                        "OpenAI", "Qwen (通义千问)", "DeepSeek(无视觉)", "Custom");

        public static final Map<String, List<String>> MODELS_BY_PROVIDER;

        static {
                MODELS_BY_PROVIDER = new LinkedHashMap<>();
                MODELS_BY_PROVIDER.put("OpenAI", List.of("gpt-4o", "gpt-4o-mini", "gpt-5.2"));
                MODELS_BY_PROVIDER.put("Qwen (通义千问)", List.of("qwen3.5-plus", "qwen3.5-flash"));
                MODELS_BY_PROVIDER.put("DeepSeek(无视觉)", List.of("deepseek-v3.2"));
                MODELS_BY_PROVIDER.put("Custom", List.of("custom-model"));
        }

        // ═══════════════════════════════════════════════════════════════
        // Config 值声明
        // ═══════════════════════════════════════════════════════════════

        public static final ForgeConfigSpec SPEC;

        // —— LLM 连接 ——
        public static final ForgeConfigSpec.ConfigValue<String> PROVIDER;
        public static final ForgeConfigSpec.ConfigValue<String> OPENAI_API_KEY;
        public static final ForgeConfigSpec.ConfigValue<String> BASE_URL;
        public static final ForgeConfigSpec.ConfigValue<String> MODEL_NAME;
        public static final ForgeConfigSpec.IntValue THINKING_MAX_LENGTH;

        // —— 截图 ——
        public static final ForgeConfigSpec.BooleanValue ENABLE_SCREENSHOT;
        public static final ForgeConfigSpec.IntValue SCREENSHOT_WIDTH;
        public static final ForgeConfigSpec.IntValue SCREENSHOT_HEIGHT;

        // —— 文本 ——
        public static final ForgeConfigSpec.ConfigValue<String> WEAPON_NAME;

        // —— 实体探查 ——
        public static final ForgeConfigSpec.IntValue ENTITIES_SCAN_RADIUS;
        public static final ForgeConfigSpec.BooleanValue ENTITIES_ONLY_CREATURES;
        public static final ForgeConfigSpec.BooleanValue ENTITIES_LINE_OF_SIGHT;
        public static final ForgeConfigSpec.IntValue ENTITIES_LOS_MAX_CHECK;
        public static final ForgeConfigSpec.BooleanValue ENTITIES_FUZZY_COUNT;

        // —— 结构探查 ——
        public static final ForgeConfigSpec.IntValue STRUCTURE_SCAN_RADIUS;
        public static final ForgeConfigSpec.IntValue STRUCTURE_MAX_RESULTS;

        // —— 右键触发 · 采集器 ——
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_BASE;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_SCREENSHOT;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_NEARBY_ENTITIES;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_PLAYER_ENVIRONMENT;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_NEARBY_STRUCTURES;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_NEARBY_POI;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_LOOKING_AT;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_SURROUNDING_BLOCKS;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_THINKING;
        public static final ForgeConfigSpec.BooleanValue RC_ENABLE_RECENT_ACTIONS;

        // —— 群系变化 · 采集器 ——
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_BASE;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_SCREENSHOT;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_NEARBY_ENTITIES;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_PLAYER_ENVIRONMENT;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_NEARBY_STRUCTURES;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_NEARBY_POI;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_LOOKING_AT;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_SURROUNDING_BLOCKS;
        public static final ForgeConfigSpec.BooleanValue BC_ENABLE_THINKING;

        // —— Agent 行为 ——
        public static final ForgeConfigSpec.BooleanValue ENABLE_PROACTIVE_TRIGGERS;
        public static final ForgeConfigSpec.BooleanValue ENABLE_BIOME_DETECTION;

        // —— LTM 召回 ——
        public static final ForgeConfigSpec.IntValue LTM_RECALL_MAIN_CANDIDATES_PER_CUE;
        public static final ForgeConfigSpec.IntValue LTM_RECALL_SANDBOX_CUES_PER_MEMORY;
        public static final ForgeConfigSpec.IntValue LTM_RECALL_CONSCIOUS_MEMORY_CAP;
        public static final ForgeConfigSpec.IntValue LTM_RECALL_RECENT_WINDOW;
        public static final ForgeConfigSpec.IntValue LTM_RECALL_RECENT_WINDOW_CAP;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_INTERVAL_BONUS_SCALE;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_INTERVAL_BONUS_STEEPNESS;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE;
        public static final ForgeConfigSpec.DoubleValue LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE;

        // —— 注意力偏好（测试版） ——
        public static final ForgeConfigSpec.DoubleValue WEIGHT_COMBAT;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_MOVEMENT;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_LABOR;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_CREATION;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_CREATURE;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_SOCIAL;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_LANDSCAPE;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_HAZARD;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_STATUS;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_CONSUMABLE;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_BLOCK;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_TOOL;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_COMMON;

        // —— 注意力过滤 (Top-K & 噪声阈值) ——
        public static final ForgeConfigSpec.IntValue TOP_K_RIGHT_CLICK;
        public static final ForgeConfigSpec.IntValue TOP_K_BIOME_CHANGE;
        public static final ForgeConfigSpec.IntValue TOP_K_CHAT;
        public static final ForgeConfigSpec.DoubleValue NOISE_THRESHOLD;
        public static final ForgeConfigSpec.BooleanValue LOOKING_AT_ATTENTION_BONUS_ENABLED;
        public static final ForgeConfigSpec.DoubleValue LOOKING_AT_ATTENTION_BONUS_VALUE;

        private static final int RECENT_RECALL_WINDOW_ABSOLUTE_CAP = 256;

        static {
                ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

                // ── LLM 连接 ──────────────────────────────────────────
                builder.push("llm");
                PROVIDER = builder
                                .comment("LLM 厂商名称")
                                .define("provider", "OpenAI");
                OPENAI_API_KEY = builder
                                .comment("API Key（必须填写）")
                                .define("apiKey", "请填写你的API Key");
                BASE_URL = builder
                                .comment("API Base URL（OpenAI 兼容接口地址）")
                                .define("baseUrl", "https://api.openai.com/v1/");
                MODEL_NAME = builder
                                .comment("模型名称")
                                .define("modelName", "gpt-4o");
                THINKING_MAX_LENGTH = builder
                                .comment("思考内容最大字符数（0=不限制，超出部分会在日志中截断）")
                                .defineInRange("thinkingMaxLength", 300, 0, 10000);
                builder.pop();

                // ── 截图 ──────────────────────────────────────────────
                builder.push("screenshot");
                ENABLE_SCREENSHOT = builder
                                .comment("是否启用截图（禁用后 LLM 仅接收文本上下文）")
                                .define("enableScreenshot", true);
                SCREENSHOT_WIDTH = builder
                                .comment("截图宽度（像素）")
                                .defineInRange("width", 320, 64, 1920);
                SCREENSHOT_HEIGHT = builder
                                .comment("截图高度（像素）")
                                .defineInRange("height", 180, 36, 1080);
                builder.pop();

                // ── 文本 ──────────────────────────────────────────────
                builder.push("text");
                WEAPON_NAME = builder
                                .comment("武器显示名称")
                                .define("weaponName", DEFAULT_WEAPON_NAME);
                builder.pop();

                // ── 实体探查 ──────────────────────────────────────────
                builder.push("entity");
                ENTITIES_SCAN_RADIUS = builder
                                .comment("实体探查扫描半径（方块数，默认16格）")
                                .defineInRange("scanRadiusBlocks", 16, 4, 64);
                ENTITIES_ONLY_CREATURES = builder
                                .comment("仅探查生物（true=只报告生物，false=同时报告掉落物、展示框等非生物实体）")
                                .define("onlyCreatures", false);
                ENTITIES_LINE_OF_SIGHT = builder
                                .comment("启用视线检测（射线检测遮挡，仅对生物生效，过滤被方块遮挡的生物）")
                                .define("lineOfSight", false);
                ENTITIES_LOS_MAX_CHECK = builder
                                .comment("视线检测最大射线数量（性能保护，按距离排序后只检测最近的 N 个生物）")
                                .defineInRange("losMaxCheck", 64, 8, 256);
                ENTITIES_FUZZY_COUNT = builder
                                .comment("模糊数量报告（true=用“一个/一些/大量”代替准确数字，false=报告准确数量）")
                                .define("fuzzyCount", false);
                builder.pop();

                // ── 结构探查 ──────────────────────────────────────────
                builder.push("structure");
                STRUCTURE_SCAN_RADIUS = builder
                                .comment("结构探查扫描半径（区块数，1区块=16格，默认5=80格）")
                                .defineInRange("scanRadiusChunks", 5, 1, 16);
                STRUCTURE_MAX_RESULTS = builder
                                .comment("结构探查最多报告的结构数量")
                                .defineInRange("maxResults", 8, 1, 32);
                builder.pop();

                // ── 右键触发 · 采集器 ─────────────────────────────────
                builder.push("trigger").push("right_click");
                RC_ENABLE_BASE = builder
                                .comment("右键触发：启用基础状态采集")
                                .define("enableBase", true);
                RC_ENABLE_SCREENSHOT = builder
                                .comment("右键触发：启用截图采集")
                                .define("enableScreenshot", true);
                RC_ENABLE_NEARBY_ENTITIES = builder
                                .comment("右键触发：启用附近实体采集")
                                .define("enableNearbyEntities", true);
                RC_ENABLE_PLAYER_ENVIRONMENT = builder
                                .comment("右键触发：启用玩家环境采集")
                                .define("enablePlayerEnvironment", true);
                RC_ENABLE_NEARBY_STRUCTURES = builder
                                .comment("右键触发：启用结构探查采集")
                                .define("enableNearbyStructures", true);
                RC_ENABLE_NEARBY_POI = builder
                                .comment("右键触发：启用POI探查采集")
                                .define("enableNearbyPoi", true);
                RC_ENABLE_LOOKING_AT = builder
                                .comment("右键触发：启用注视目标采集")
                                .define("enableLookingAt", true);
                RC_ENABLE_SURROUNDING_BLOCKS = builder
                                .comment("右键触发：启用周围方块/地形采集")
                                .define("enableSurroundingBlocks", true);
                RC_ENABLE_THINKING = builder
                                .comment("右键触发：启用思考模式（LLM 先思考再回复，思考内容仅保留在日志中）")
                                .define("enableThinking", false);
                RC_ENABLE_RECENT_ACTIONS = builder
                                .comment("右键触发：启用近期动作流总览（从 ActionBuffer 中抽取内容，非常推荐开启）")
                                .define("enableRecentActions", true);
                builder.pop(); // right_click

                // ── 群系变化 · 采集器 ─────────────────────────────────
                builder.push("biome_change");
                BC_ENABLE_BASE = builder
                                .comment("群系变化：启用基础状态采集")
                                .define("enableBase", true);
                BC_ENABLE_SCREENSHOT = builder
                                .comment("群系变化：启用截图采集")
                                .define("enableScreenshot", false);
                BC_ENABLE_NEARBY_ENTITIES = builder
                                .comment("群系变化：启用附近实体采集")
                                .define("enableNearbyEntities", true);
                BC_ENABLE_PLAYER_ENVIRONMENT = builder
                                .comment("群系变化：启用玩家环境采集")
                                .define("enablePlayerEnvironment", true);
                BC_ENABLE_NEARBY_STRUCTURES = builder
                                .comment("群系变化：启用结构探查采集")
                                .define("enableNearbyStructures", true);
                BC_ENABLE_NEARBY_POI = builder
                                .comment("群系变化：启用POI探查采集")
                                .define("enableNearbyPoi", true);
                BC_ENABLE_LOOKING_AT = builder
                                .comment("群系变化：启用注视目标采集")
                                .define("enableLookingAt", false);
                BC_ENABLE_SURROUNDING_BLOCKS = builder
                                .comment("群系变化：启用周围方块/地形采集")
                                .define("enableSurroundingBlocks", true);
                BC_ENABLE_THINKING = builder
                                .comment("群系变化：启用思考模式（LLM 先思考再回复，思考内容仅保留在日志中）")
                                .define("enableThinking", false);
                builder.pop(); // biome_change
                builder.pop(); // trigger

                // ── 注意力偏好（测试版） ────────────────────────────────
                builder.push("attention_weights");
                builder.comment(" 注意力偏好权重设置 (仅在测试版开放，后续将绑定 Agent 性格)");
                WEIGHT_COMBAT = builder.defineInRange("combat", 1.0, 0.0, 10.0);
                WEIGHT_MOVEMENT = builder.defineInRange("movement", 1.0, 0.0, 10.0);
                WEIGHT_LABOR = builder.defineInRange("labor", 1.0, 0.0, 10.0);
                WEIGHT_CREATION = builder.defineInRange("creation", 1.0, 0.0, 10.0);
                WEIGHT_CREATURE = builder.defineInRange("creature", 1.0, 0.0, 10.0);
                WEIGHT_SOCIAL = builder.defineInRange("social", 1.0, 0.0, 10.0);
                WEIGHT_LANDSCAPE = builder.defineInRange("landscape", 1.0, 0.0, 10.0);
                WEIGHT_HAZARD = builder.defineInRange("hazard", 1.0, 0.0, 10.0);
                WEIGHT_STATUS = builder.defineInRange("status", 1.0, 0.0, 10.0);
                WEIGHT_CONSUMABLE = builder.defineInRange("consumable", 1.0, 0.0, 10.0);
                WEIGHT_BLOCK = builder.defineInRange("block", 1.0, 0.0, 10.0);
                WEIGHT_TOOL = builder.defineInRange("tool", 1.0, 0.0, 10.0);
                WEIGHT_COMMON = builder.defineInRange("common", 1.0, 0.0, 10.0);
                builder.pop();

                // ── 注意力过滤 (Top-K & 噪声阈值) ──────────────────────────────────
                builder.push("attention_filter");
                builder.comment(" 各触发器的环境信息截断上限 (Top-K) 与噪声过滤阈值");
                TOP_K_RIGHT_CLICK = builder.comment("右键触发时，喂给大模型的环境信息条数上限").defineInRange("rightClick", 7, 1, 20);
                TOP_K_BIOME_CHANGE = builder.comment("群系变化时，喂给大模型的环境信息条数上限").defineInRange("biomeChange", 5, 1, 20);
                TOP_K_CHAT = builder.comment("对话触发时，喂给大模型的环境信息条数上限").defineInRange("chat", 7, 1, 20);
                NOISE_THRESHOLD = builder.comment("低于此得分的上下文条目将被直接丢弃 (0.0–1.0)").defineInRange("noiseThreshold", 0.1, 0.0, 1.0);
                LOOKING_AT_ATTENTION_BONUS_ENABLED = builder
                                .comment("是否对玩家准心直接指向的目标额外施加注意力加分")
                                .define("lookingAtBonusEnabled", true);
                LOOKING_AT_ATTENTION_BONUS_VALUE = builder
                                .comment("当启用准心目标加分时，额外添加到最终注意力分数上的绝对值加分")
                                .defineInRange("lookingAtBonusValue", 0.18, 0.0, 2.0);
                builder.pop();

                // ── Agent 行为 ─────────────────────────────────────────
                builder.push("agent");
                ENABLE_PROACTIVE_TRIGGERS = builder
                                .comment("启用主动触发器总开关")
                                .define("enableProactiveTriggers", true);
                ENABLE_BIOME_DETECTION = builder
                                .comment("启用被动群系变化检测（玩家进入新群系时自动触发）")
                                .define("enableBiomeDetection", true);

                builder.push("memory_recall");
                LTM_RECALL_MAIN_CANDIDATES_PER_CUE = builder
                                .comment("CUSTOM 召回预设默认值：主召回阶段中，每个 cue 最多可提起多少条候选记忆；仅在 agent_state.json 未提供 recall_tuning 时使用")
                                .defineInRange("mainCandidatesPerCue", 1, 1, 6);
                LTM_RECALL_SANDBOX_CUES_PER_MEMORY = builder
                                .comment("CUSTOM 召回预设默认值：sandbox 联想阶段中，每条表层记忆最多回灌多少个联想 cue；仅在 agent_state.json 未提供 recall_tuning 时使用")
                                .defineInRange("sandboxCuesPerMemory", 2, 0, 4);
                LTM_RECALL_CONSCIOUS_MEMORY_CAP = builder
                                .comment("CUSTOM 召回预设默认值：最终最多允许多少条记忆进入表层意识；仅在 agent_state.json 未提供 recall_tuning 时使用")
                                .defineInRange("consciousMemoryCap", 3, 0, 8);
                LTM_RECALL_RECENT_WINDOW = builder
                                .comment("最近召回记忆抑制窗口：越大越不容易连续几轮重复同一段记忆；实际生效值还会受 recentRecallWindowCap 限制")
                                .defineInRange("recentRecallWindow", 6, 1, RECENT_RECALL_WINDOW_ABSOLUTE_CAP);
                LTM_RECALL_RECENT_WINDOW_CAP = builder
                                .comment("最近召回窗口上限：用于限制 recentRecallWindow 的最大有效值，便于在运行时快速收紧或放宽抑制范围")
                                .defineInRange("recentRecallWindowCap", 20, 1, RECENT_RECALL_WINDOW_ABSOLUTE_CAP);
                LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE = builder
                                .comment("植入记忆保底被补入 conscious/retained 时的注入分数；越高越容易压过本轮普通召回结果")
                                .defineInRange("implantedFallbackEntryScore", 1.25, 0.0, 3.0);
                LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE = builder
                                .comment("prompt fill 阀门的整体开合系数；越低越不愿意把已意识到的记忆说出口，越高越容易真的填入 prompt")
                                .defineInRange("promptFillProbabilityScale", 1.0, 0.0, 3.0);
                LTM_RECALL_TAG_POOL_HALF_LIFE_DAYS = builder
                                .comment("tag_pool 半衰期，单位为游戏内天数；越大代表 cue 保留越久")
                                .defineInRange("tagPoolHalfLifeDays", 0.40, 0.05, 30.0);
                LTM_RECALL_FERMENTATION_HALF_LIFE_DAYS = builder
                                .comment("fermentation_buffer 半衰期，单位为游戏内天数；越大代表潜意识候选记忆保留越久")
                                .defineInRange("fermentationHalfLifeDays", 2.50, 0.10, 120.0);
                LTM_RECALL_INTERVAL_BONUS_SCALE = builder
                                .comment("长间隔补偿的整体缩放系数；越大代表隔一段时间后新注入 cue 的额外加成越高")
                                .defineInRange("intervalBonusScale", 0.95, 0.0, 10.0);
                LTM_RECALL_INTERVAL_BONUS_STEEPNESS = builder
                                .comment("长间隔补偿曲线的陡峭程度；越大代表少量游戏内时间也能更快接近补偿上限")
                                .defineInRange("intervalBonusSteepness", 4.0, 0.1, 20.0);
                LTM_RECALL_SYNTHESIZED_MEMORY_HALF_LIFE = builder
                                .comment("合成记忆时间衰减的半衰期，单位为游戏内天数；当前 recall 不再回退到现实时间衰减")
                                .defineInRange("synthesizedMemoryHalfLife", 63.0, 1.0, 240.0);
                LTM_RECALL_DESIRE_PROFILE_BONUS_SCALE = builder
                                .comment("当前渴望标签在 focus profile 构造阶段的额外加权系数；越大代表渴望更容易主导本轮焦点分布")
                                .defineInRange("desireProfileBonusScale", 0.25, 0.0, 2.0);
                LTM_RECALL_DESIRED_FOCUS_BONUS_SCALE = builder
                                .comment("当前渴望标签在记忆评分阶段的额外加分系数；越大代表带有渴望 focus 的记忆更容易入选")
                                .defineInRange("desiredFocusBonusScale", 0.18, 0.0, 2.0);
                builder.pop();
                builder.pop();

                SPEC = builder.build();
        }

        private TalkingWeaponConfig() {
        }

        /**
         * 在 Mod 构造函数中调用，注册 client 配置文件。
         */
        public static void register() {
                ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC, "talking_weapon-client.toml");
        }

        public static String getResolvedWeaponName() {
                return resolvedWeaponName;
        }

        public static int getRecentRecallWindowCapValue() {
                return Math.max(1, Math.min(RECENT_RECALL_WINDOW_ABSOLUTE_CAP, LTM_RECALL_RECENT_WINDOW_CAP.get()));
        }

        public static int getEffectiveRecentRecallWindow() {
                return Math.max(1, Math.min(LTM_RECALL_RECENT_WINDOW.get(), getRecentRecallWindowCapValue()));
        }

        public static double getImplantedFallbackEntryScoreValue() {
                return Math.max(0.0, Math.min(3.0, LTM_RECALL_IMPLANTED_FALLBACK_ENTRY_SCORE.get()));
        }

        public static double getPromptFillProbabilityScaleValue() {
                return Math.max(0.0, Math.min(3.0, LTM_RECALL_PROMPT_FILL_PROBABILITY_SCALE.get()));
        }

        @SubscribeEvent
        public static void onConfigLoading(ModConfigEvent.Loading event) {
                syncResolvedValues(event);
        }

        @SubscribeEvent
        public static void onConfigReloading(ModConfigEvent.Reloading event) {
                syncResolvedValues(event);
        }

        private static void syncResolvedValues(ModConfigEvent event) {
                if (event.getConfig().getSpec() != SPEC) {
                        return;
                }

                try {
                        resolvedWeaponName = sanitizeWeaponName(WEAPON_NAME.get());
                } catch (Throwable t) {
                        resolvedWeaponName = DEFAULT_WEAPON_NAME;
                        System.err.println("[TalkingWeapon] Failed to resolve weaponName from config: " + t);
                }

                syncRecallParametersFromWorkspace();
        }

        private static String sanitizeWeaponName(String value) {
                return value == null ? "" : value;
        }

        private static void syncRecallParametersFromWorkspace() {
                if (syncingRecallParametersFromWorkspace) {
                        return;
                }

                syncingRecallParametersFromWorkspace = true;
                try {
                        boolean loaded = StudioMemoryRecallTuningFile.applyWorkspaceValuesToRuntime(true);
                        if (!loaded) {
                                StudioMemoryRecallTuningFile.ensureWorkspaceFileExists();
                        }
                } catch (Throwable t) {
                        System.err.println("[TalkingWeapon] Failed to sync recall parameters from workspace: " + t);
                } finally {
                        syncingRecallParametersFromWorkspace = false;
                }
        }
}
