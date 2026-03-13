package dreamdust.talking_weapon.basic_talking_weapon.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局配置：基于 Forge Config 系统，对应 talking_weapon-client.toml。
 */
public final class TalkingWeaponConfig {

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
        public static final ForgeConfigSpec.IntValue MAX_TOKENS;
        public static final ForgeConfigSpec.IntValue THINKING_MAX_LENGTH;

        // —— 截图 ——
        public static final ForgeConfigSpec.BooleanValue ENABLE_SCREENSHOT;
        public static final ForgeConfigSpec.IntValue SCREENSHOT_WIDTH;
        public static final ForgeConfigSpec.IntValue SCREENSHOT_HEIGHT;

        // —— 文本 ——
        public static final ForgeConfigSpec.ConfigValue<String> WEAPON_NAME;

        // —— 实体探查 ——
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

        // —— 注意力偏好（测试版） ——
        public static final ForgeConfigSpec.DoubleValue WEIGHT_COMBAT;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_MOVEMENT;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_LABOR;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_CREATION;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_SOCIAL;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_LANDSCAPE;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_HAZARD;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_STATUS;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_CONSUMABLE;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_BLOCK;
        public static final ForgeConfigSpec.DoubleValue WEIGHT_TOOL;

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
                MAX_TOKENS = builder
                                .comment("最大生成 token 数")
                                .defineInRange("maxTokens", 256, 1, 4096);
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
                                .define("weaponName", "塞勒瑞斯");
                builder.pop();

                // ── 实体探查 ──────────────────────────────────────────
                builder.push("entity");
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
                WEIGHT_SOCIAL = builder.defineInRange("social", 1.0, 0.0, 10.0);
                WEIGHT_LANDSCAPE = builder.defineInRange("landscape", 1.0, 0.0, 10.0);
                WEIGHT_HAZARD = builder.defineInRange("hazard", 1.0, 0.0, 10.0);
                WEIGHT_STATUS = builder.defineInRange("status", 1.0, 0.0, 10.0);
                WEIGHT_CONSUMABLE = builder.defineInRange("consumable", 1.0, 0.0, 10.0);
                WEIGHT_BLOCK = builder.defineInRange("block", 1.0, 0.0, 10.0);
                WEIGHT_TOOL = builder.defineInRange("tool", 1.0, 0.0, 10.0);
                builder.pop();

                // ── Agent 行为 ─────────────────────────────────────────
                builder.push("agent");
                ENABLE_PROACTIVE_TRIGGERS = builder
                                .comment("启用主动触发器总开关")
                                .define("enableProactiveTriggers", true);
                ENABLE_BIOME_DETECTION = builder
                                .comment("启用被动群系变化检测（玩家进入新群系时自动触发）")
                                .define("enableBiomeDetection", true);
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
}
