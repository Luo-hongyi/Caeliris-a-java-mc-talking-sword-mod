package dreamdust.talking_weapon.basic_talking_weapon.config;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.studio.NativeCatalogIconExporter;
import dreamdust.talking_weapon.basic_talking_weapon.studio.NativeCatalogSnapshotExporter;
import dreamdust.talking_weapon.basic_talking_weapon.studio.NativeTagDescriptionSeeder;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioErrorLogger;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceExporter;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePublisher;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeTagViewExporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 游戏内配置界面：在 Mod 列表点击「配置」时弹出。
 * 使用 ContainerObjectSelectionList 实现可滚动布局，
 * 标题固定在顶部，保存/取消按钮固定在底部。
 */
public class TalkingWeaponConfigScreen extends Screen {

    private final Screen parent;

    // 输入框引用（用于保存时读取值）
    private EditBox widthBox;
    private EditBox heightBox;

    // 结构探查输入框
    private EditBox structureRadiusBox;
    private EditBox structureMaxResultsBox;

    // 厂商选择
    private int providerIndex;
    // 模型循环选择
    private int modelIndex;

    // ── 右键触发 · 采集器开关 ──
    private boolean rcBase;
    private boolean rcScreenshot;
    private boolean rcNearbyEntities;
    private boolean rcPlayerEnvironment;
    private boolean rcNearbyStructures;
    private boolean rcNearbyPoi;
    private boolean rcLookingAt;
    private boolean rcSurroundingBlocks;
    private boolean rcThinking;
    private boolean rcRecentActions;

    // ── 群系变化 · 采集器开关 ──
    private boolean bcBase;
    private boolean bcScreenshot;
    private boolean bcNearbyEntities;
    private boolean bcPlayerEnvironment;
    private boolean bcNearbyStructures;
    private boolean bcNearbyPoi;
    private boolean bcLookingAt;
    private boolean bcSurroundingBlocks;
    private boolean bcThinking;

    // ── 实体探查 ──
    private boolean entitiesOnlyCreatures;
    private boolean entitiesLineOfSight;
    private boolean entitiesFuzzyCount;

    // ── 注意力过滤 (Top-K) ──
    private EditBox rcTopKBox;
    private EditBox bcTopKBox;
    private EditBox chatTopKBox;
    private EditBox noiseThresholdBox;

    // ── LLM 思考模式 ──
    private EditBox thinkingMaxLenBox;

    // ── Agent 行为开关 ──
    private boolean enableProactiveTriggers;
    private boolean enableBiomeDetection;

    private ConfigList configList;

    // 用于更新的按钮引用
    private Button providerBtn;
    private Button modelBtn;

    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 36;

    private static final List<String> PROVIDERS = TalkingWeaponConfig.AVAILABLE_PROVIDERS;

    public TalkingWeaponConfigScreen(Screen parent) {
        super(Component.literal("⚔ 会说话的武器 · 设置"));
        this.parent = parent;

        String curProvider = TalkingWeaponConfig.PROVIDER.get();
        this.providerIndex = PROVIDERS.indexOf(curProvider);
        if (this.providerIndex < 0)
            this.providerIndex = 0;

        String curModel = TalkingWeaponConfig.MODEL_NAME.get();
        List<String> models = getModelsForCurrentProvider();
        this.modelIndex = models.indexOf(curModel);
        if (this.modelIndex < 0)
            this.modelIndex = 0;

        this.rcBase = TalkingWeaponConfig.RC_ENABLE_BASE.get();
        this.rcScreenshot = TalkingWeaponConfig.RC_ENABLE_SCREENSHOT.get();
        this.rcNearbyEntities = TalkingWeaponConfig.RC_ENABLE_NEARBY_ENTITIES.get();
        this.rcPlayerEnvironment = TalkingWeaponConfig.RC_ENABLE_PLAYER_ENVIRONMENT.get();
        this.rcNearbyStructures = TalkingWeaponConfig.RC_ENABLE_NEARBY_STRUCTURES.get();
        this.rcNearbyPoi = TalkingWeaponConfig.RC_ENABLE_NEARBY_POI.get();
        this.rcLookingAt = TalkingWeaponConfig.RC_ENABLE_LOOKING_AT.get();
        this.rcSurroundingBlocks = TalkingWeaponConfig.RC_ENABLE_SURROUNDING_BLOCKS.get();
        this.rcThinking = TalkingWeaponConfig.RC_ENABLE_THINKING.get();
        this.rcRecentActions = TalkingWeaponConfig.RC_ENABLE_RECENT_ACTIONS.get();

        this.bcBase = TalkingWeaponConfig.BC_ENABLE_BASE.get();
        this.bcScreenshot = TalkingWeaponConfig.BC_ENABLE_SCREENSHOT.get();
        this.bcNearbyEntities = TalkingWeaponConfig.BC_ENABLE_NEARBY_ENTITIES.get();
        this.bcPlayerEnvironment = TalkingWeaponConfig.BC_ENABLE_PLAYER_ENVIRONMENT.get();
        this.bcNearbyStructures = TalkingWeaponConfig.BC_ENABLE_NEARBY_STRUCTURES.get();
        this.bcNearbyPoi = TalkingWeaponConfig.BC_ENABLE_NEARBY_POI.get();
        this.bcLookingAt = TalkingWeaponConfig.BC_ENABLE_LOOKING_AT.get();
        this.bcSurroundingBlocks = TalkingWeaponConfig.BC_ENABLE_SURROUNDING_BLOCKS.get();
        this.bcThinking = TalkingWeaponConfig.BC_ENABLE_THINKING.get();

        this.entitiesOnlyCreatures = TalkingWeaponConfig.ENTITIES_ONLY_CREATURES.get();
        this.entitiesLineOfSight = TalkingWeaponConfig.ENTITIES_LINE_OF_SIGHT.get();
        this.entitiesFuzzyCount = TalkingWeaponConfig.ENTITIES_FUZZY_COUNT.get();

        this.enableProactiveTriggers = TalkingWeaponConfig.ENABLE_PROACTIVE_TRIGGERS.get();
        this.enableBiomeDetection = TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get();
    }

    @Override
    protected void init() {
        // ═══ 可滚动列表（顶部留 HEADER_HEIGHT，底部留 FOOTER_HEIGHT） ═══
        configList = new ConfigList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT, 26);
        addWidget(configList);

        int rowWidth = Math.min(width - 40, 400);
        int left = (width - rowWidth) / 2;
        int col2 = left + rowWidth / 2 + 10;
        int halfW = rowWidth / 2 - 14;

        // ── 厂商选择 ──
        configList.addEntry(new LabelEntry("§e▸ LLM 设置"));

        Button prevProvider = Button.builder(Component.literal("< "), btn -> {
            providerIndex = (providerIndex - 1 + PROVIDERS.size()) % PROVIDERS.size();
            modelIndex = 0;
            providerBtn.setMessage(Component.literal(PROVIDERS.get(providerIndex)));
            modelBtn.setMessage(Component.literal(getModelsForCurrentProvider().get(modelIndex)));
        }).bounds(0, 0, 20, 20).build();

        providerBtn = Button.builder(Component.literal(PROVIDERS.get(providerIndex)), btn -> {
            providerIndex = (providerIndex + 1) % PROVIDERS.size();
            modelIndex = 0;
            btn.setMessage(Component.literal(PROVIDERS.get(providerIndex)));
            modelBtn.setMessage(Component.literal(getModelsForCurrentProvider().get(modelIndex)));
        }).bounds(0, 0, halfW - 44, 20).build();

        Button nextProvider = Button.builder(Component.literal(" >"), btn -> {
            providerIndex = (providerIndex + 1) % PROVIDERS.size();
            modelIndex = 0;
            providerBtn.setMessage(Component.literal(PROVIDERS.get(providerIndex)));
            modelBtn.setMessage(Component.literal(getModelsForCurrentProvider().get(modelIndex)));
        }).bounds(0, 0, 20, 20).build();

        configList.addEntry(new WidgetRowEntry("厂商选择", left, rowWidth, prevProvider, providerBtn, nextProvider));

        // ── 模型选择 ──
        Button prevModel = Button.builder(Component.literal("< "), btn -> {
            List<String> models = getModelsForCurrentProvider();
            modelIndex = (modelIndex - 1 + models.size()) % models.size();
            modelBtn.setMessage(Component.literal(models.get(modelIndex)));
        }).bounds(0, 0, 20, 20).build();

        modelBtn = Button.builder(Component.literal(getModelsForCurrentProvider().get(modelIndex)), btn -> {
            List<String> models = getModelsForCurrentProvider();
            modelIndex = (modelIndex + 1) % models.size();
            btn.setMessage(Component.literal(models.get(modelIndex)));
        }).bounds(0, 0, halfW - 44, 20).build();

        Button nextModel = Button.builder(Component.literal(" >"), btn -> {
            List<String> models = getModelsForCurrentProvider();
            modelIndex = (modelIndex + 1) % models.size();
            modelBtn.setMessage(Component.literal(models.get(modelIndex)));
        }).bounds(0, 0, 20, 20).build();

        configList.addEntry(new WidgetRowEntry("模型选择", left, rowWidth, prevModel, modelBtn, nextModel));

        // ── 思考长度上限（全局） ──
        thinkingMaxLenBox = new EditBox(font, 0, 0, 80, 20, Component.literal("思考上限"));
        thinkingMaxLenBox.setValue(String.valueOf(TalkingWeaponConfig.THINKING_MAX_LENGTH.get()));
        thinkingMaxLenBox.setMaxLength(5);
        thinkingMaxLenBox.setCanLoseFocus(true);
        configList.addEntry(new InputEntry("思考长度上限 (token)", left, rowWidth, thinkingMaxLenBox, "0=不限"));

        configList.addEntry(new LabelEntry("§e▸ 截图设置"));

        widthBox = new EditBox(font, 0, 0, 80, 20, Component.literal("宽度"));
        widthBox.setValue(String.valueOf(TalkingWeaponConfig.SCREENSHOT_WIDTH.get()));
        widthBox.setMaxLength(4);
        widthBox.setCanLoseFocus(true);

        heightBox = new EditBox(font, 0, 0, 80, 20, Component.literal("高度"));
        heightBox.setValue(String.valueOf(TalkingWeaponConfig.SCREENSHOT_HEIGHT.get()));
        heightBox.setMaxLength(4);
        heightBox.setCanLoseFocus(true);

        configList.addEntry(new TwoWidgetEntry("截图宽 × 高", left, rowWidth, widthBox, heightBox));

        // ── 结构探查设置 ──
        configList.addEntry(new LabelEntry("§e▸ 结构探查设置"));

        structureRadiusBox = new EditBox(font, 0, 0, 80, 20, Component.literal("扫描半径"));
        structureRadiusBox.setValue(String.valueOf(TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get()));
        structureRadiusBox.setMaxLength(2);
        structureRadiusBox.setCanLoseFocus(true);

        structureMaxResultsBox = new EditBox(font, 0, 0, 80, 20, Component.literal("最大数量"));
        structureMaxResultsBox.setValue(String.valueOf(TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get()));
        structureMaxResultsBox.setMaxLength(2);
        structureMaxResultsBox.setCanLoseFocus(true);

        configList.addEntry(
                new TwoWidgetEntry("半径(区块) / 数量上限", left, rowWidth, structureRadiusBox, structureMaxResultsBox));

        // ── 实体探查设置 ──
        configList.addEntry(new LabelEntry("§e▸ 实体探查设置"));
        configList.addEntry(
                new ToggleEntry("仅探查生物", left, rowWidth, entitiesOnlyCreatures, v -> entitiesOnlyCreatures = v));
        configList.addEntry(
                new ToggleEntry("视线检测（仅生物）", left, rowWidth, entitiesLineOfSight, v -> entitiesLineOfSight = v));
        configList.addEntry(new ToggleEntry("模糊数量报告", left, rowWidth, entitiesFuzzyCount, v -> entitiesFuzzyCount = v));

        // ── 注意力过滤设置 (Top-K & 噪声阈值) ──
        configList.addEntry(new LabelEntry("§e▸ 注意力过滤上限 (Top-K)"));
        
        rcTopKBox = new EditBox(font, 0, 0, 80, 20, Component.literal("右键触发"));
        rcTopKBox.setValue(String.valueOf(TalkingWeaponConfig.TOP_K_RIGHT_CLICK.get()));
        rcTopKBox.setMaxLength(3);
        rcTopKBox.setCanLoseFocus(true);
        configList.addEntry(new InputEntry("右键触发上限", left, rowWidth, rcTopKBox, "默认 7"));

        bcTopKBox = new EditBox(font, 0, 0, 80, 20, Component.literal("群系变化"));
        bcTopKBox.setValue(String.valueOf(TalkingWeaponConfig.TOP_K_BIOME_CHANGE.get()));
        bcTopKBox.setMaxLength(3);
        bcTopKBox.setCanLoseFocus(true);
        configList.addEntry(new InputEntry("群系变化上限", left, rowWidth, bcTopKBox, "默认 5"));

        chatTopKBox = new EditBox(font, 0, 0, 80, 20, Component.literal("玩家对话"));
        chatTopKBox.setValue(String.valueOf(TalkingWeaponConfig.TOP_K_CHAT.get()));
        chatTopKBox.setMaxLength(3);
        chatTopKBox.setCanLoseFocus(true);
        configList.addEntry(new InputEntry("对话触发上限", left, rowWidth, chatTopKBox, "默认 7"));

        noiseThresholdBox = new EditBox(font, 0, 0, 80, 20, Component.literal("噪声阈值"));
        noiseThresholdBox.setValue(String.valueOf(TalkingWeaponConfig.NOISE_THRESHOLD.get()));
        noiseThresholdBox.setMaxLength(5);
        noiseThresholdBox.setCanLoseFocus(true);
        configList.addEntry(new InputEntry("噪声过滤阈值", left, rowWidth, noiseThresholdBox, "0.0–1.0"));

        // ── 右键触发 · 采集器 ──
        configList.addEntry(new LabelEntry("§e▸ 右键触发 · 采集器"));

        configList.addEntry(new ToggleEntry("基础状态采集", left, rowWidth, rcBase, v -> rcBase = v));
        configList.addEntry(new ToggleEntry("截图采集", left, rowWidth, rcScreenshot, v -> rcScreenshot = v));
        configList.addEntry(new ToggleEntry("附近实体采集", left, rowWidth, rcNearbyEntities, v -> rcNearbyEntities = v));
        configList
                .addEntry(new ToggleEntry("玩家环境采集", left, rowWidth, rcPlayerEnvironment, v -> rcPlayerEnvironment = v));
        configList.addEntry(new ToggleEntry("结构探查采集", left, rowWidth, rcNearbyStructures, v -> rcNearbyStructures = v));
        configList.addEntry(new ToggleEntry("POI探查采集", left, rowWidth, rcNearbyPoi, v -> rcNearbyPoi = v));
        configList.addEntry(new ToggleEntry("注视目标采集", left, rowWidth, rcLookingAt, v -> rcLookingAt = v));
        configList.addEntry(
                new ToggleEntry("周围方块/地形采集", left, rowWidth, rcSurroundingBlocks, v -> rcSurroundingBlocks = v));
        configList.addEntry(new ToggleEntry("启用思考模式", left, rowWidth, rcThinking, v -> rcThinking = v));
        configList.addEntry(new ToggleEntry("近期动作流采集", left, rowWidth, rcRecentActions, v -> rcRecentActions = v));

        // ── 群系变化 · 采集器 ──
        configList.addEntry(new LabelEntry("§e▸ 群系变化 · 采集器"));

        configList.addEntry(new ToggleEntry("基础状态采集", left, rowWidth, bcBase, v -> bcBase = v));
        configList.addEntry(new ToggleEntry("截图采集", left, rowWidth, bcScreenshot, v -> bcScreenshot = v));
        configList.addEntry(new ToggleEntry("附近实体采集", left, rowWidth, bcNearbyEntities, v -> bcNearbyEntities = v));
        configList
                .addEntry(new ToggleEntry("玩家环境采集", left, rowWidth, bcPlayerEnvironment, v -> bcPlayerEnvironment = v));
        configList.addEntry(new ToggleEntry("结构探查采集", left, rowWidth, bcNearbyStructures, v -> bcNearbyStructures = v));
        configList.addEntry(new ToggleEntry("POI探查采集", left, rowWidth, bcNearbyPoi, v -> bcNearbyPoi = v));
        configList.addEntry(new ToggleEntry("注视目标采集", left, rowWidth, bcLookingAt, v -> bcLookingAt = v));
        configList.addEntry(
                new ToggleEntry("周围方块/地形采集", left, rowWidth, bcSurroundingBlocks, v -> bcSurroundingBlocks = v));
        configList.addEntry(new ToggleEntry("启用思考模式", left, rowWidth, bcThinking, v -> bcThinking = v));

        // ── Agent 行为开关 ──
        configList.addEntry(new LabelEntry("§e▸ Agent 行为"));

        configList.addEntry(new ToggleEntry("主动触发（总开关）", left, rowWidth, enableProactiveTriggers,
                v -> enableProactiveTriggers = v));
        configList.addEntry(
                new ToggleEntry("群系变化检测", left, rowWidth, enableBiomeDetection, v -> enableBiomeDetection = v));

        configList.addEntry(new LabelEntry(""));
        Button openToml = Button.builder(Component.literal("打开配置文件(.toml)"), btn -> openConfigFile())
                .bounds(0, 0, 210, 20).build();
        configList.addEntry(new SingleWidgetEntry(left, rowWidth, openToml));

        // ── 提示词管理 ──
        configList.addEntry(new LabelEntry("§e▸ 提示词管理 (prompts.json)"));

        Button openPrompts = Button.builder(Component.literal("打开提示词文件"), btn -> {
            Util.getPlatform().openUri(PromptRegistry.getConfigPath().toUri());
        }).bounds(0, 0, 100, 20).build();

        Button reloadPrompts = Button.builder(Component.literal("§b热重载提示词"), btn -> {
            PromptRegistry.reload();
            btn.setMessage(Component.literal("§a已完成重载"));
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, openPrompts, reloadPrompts));

        // ── 客观重要度管理 ──
        configList.addEntry(new LabelEntry("§e▸ 客观重要度配置 (importance.json)"));
        configList.addEntry(new LabelEntry("§7调整 Agent 对各类游戏事件（如战斗、光照、天气）的关注权重，0~1，数值越高越优先进入提示词。"));

        Button openImportance = Button.builder(Component.literal("打开重要度文件"), btn -> {
            Util.getPlatform().openUri(
                    dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry.getConfigPath().toUri());
        }).bounds(0, 0, 100, 20).build();

        Button reloadImportance = Button.builder(Component.literal("§b热重载重要度"), btn -> {
            dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry.reload();
            btn.setMessage(Component.literal("§a已完成重载"));
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, openImportance, reloadImportance));

        // ── Studio 快照导出 ──
        configList.addEntry(new LabelEntry("§e▸ Studio 快照"));

        Button exportNativeCatalog = Button.builder(Component.literal("导出 Native Tag 快照"), btn -> {
            try {
                NativeCatalogSnapshotExporter.ExportResult result = NativeCatalogSnapshotExporter.exportSnapshot();
                btn.setMessage(Component.literal("§a已导出 " + result.entryCount + " 条"));
            } catch (Exception e) {
                handleStudioActionError(btn, "export native catalog", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button openNativeCatalog = Button.builder(Component.literal("打开快照文件"), btn -> {
            Util.getPlatform().openUri(NativeCatalogSnapshotExporter.getSnapshotPath().toUri());
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, exportNativeCatalog, openNativeCatalog));

        Button exportRuntimeTagView = Button.builder(Component.literal("导出 Tag 合并视图"), btn -> {
            try {
                StudioRuntimeTagViewExporter.ExportResult result = StudioRuntimeTagViewExporter.exportMergedView();
                btn.setMessage(Component.literal("§a" + result.tagCount + " 条 / " + result.issueCount + " 问题"));
            } catch (Exception e) {
                handleStudioActionError(btn, "export runtime tag view", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button openRuntimeTagView = Button.builder(Component.literal("打开合并视图"), btn -> {
            Util.getPlatform().openUri(StudioRuntimeTagViewExporter.getOutputPath().toUri());
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, exportRuntimeTagView, openRuntimeTagView));

        Button seedNativeDescriptions = Button.builder(Component.literal("补全 Native 描述"), btn -> {
            try {
                NativeTagDescriptionSeeder.SeedResult result = NativeTagDescriptionSeeder.seedMissingNativeDescriptions();
                btn.setMessage(Component.literal("§a新增 " + result.addedCount + " 条"));
            } catch (Exception e) {
                handleStudioActionError(btn, "seed native descriptions", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button openTagDescriptions = Button.builder(Component.literal("打开描述文件"), btn -> {
            Util.getPlatform().openUri(TagDescriptionDictionary.getConfigPath().toUri());
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, seedNativeDescriptions, openTagDescriptions));

        Button exportNativeIcons = Button.builder(Component.literal("导出 Native 图标"), btn -> {
            try {
                NativeCatalogIconExporter.ExportResult result = NativeCatalogIconExporter.exportAllIcons();
                btn.setMessage(Component.literal("§a" + result.renderedCount + "+" + result.placeholderCount));
            } catch (Exception e) {
                handleStudioActionError(btn, "export native icons", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button openNativeIcons = Button.builder(Component.literal("打开图标目录"), btn -> {
            Util.getPlatform().openUri(NativeCatalogSnapshotExporter.getIconDirectory().toUri());
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, exportNativeIcons, openNativeIcons));

        Button exportStudioWorkspace = Button.builder(Component.literal("刷新 Studio 文件夹"), btn -> {
            try {
                StudioWorkspaceExporter.ExportResult result = StudioWorkspaceExporter.exportWorkspace();
                btn.setMessage(Component.literal("§a" + result.fileCount + " 文件已就位"));
            } catch (Exception e) {
                handleStudioActionError(btn, "export studio workspace", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button openStudioWorkspace = Button.builder(Component.literal("打开 Studio 文件夹"), btn -> {
            Util.getPlatform().openUri(StudioWorkspaceExporter.getWorkspaceRoot().toUri());
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, exportStudioWorkspace, openStudioWorkspace));

        Button publishStudioWorkspace = Button.builder(Component.literal("§b应用 Studio 工作区"), btn -> {
            try {
                StudioWorkspacePublisher.PublishResult result = StudioWorkspacePublisher.publishWorkspace();
                btn.setMessage(Component.literal("§a已重载 " + result.publishedFiles.size() + " 文件"));
            } catch (Exception e) {
                handleStudioActionError(btn, "publish studio workspace", e);
            }
        }).bounds(0, 0, rowWidth - 8, 20).build();

        configList.addEntry(new SingleWidgetEntry(left, rowWidth, publishStudioWorkspace));

        Button openStudioErrorLog = Button.builder(Component.literal("打开 Studio 错误日志"), btn -> {
            Path latestLog = StudioErrorLogger.getLatestLogPath().toAbsolutePath();
            if (Files.isRegularFile(latestLog)) {
                Util.getPlatform().openUri(latestLog.toUri());
            } else {
                btn.setMessage(Component.literal("§7暂无 Studio 错误日志"));
            }
        }).bounds(0, 0, rowWidth - 8, 20).build();

        configList.addEntry(new SingleWidgetEntry(left, rowWidth, openStudioErrorLog));

        // ── 日志管理 ──
        configList.addEntry(new LabelEntry("§e▸ 日志管理"));

        Button openLogDir = Button.builder(Component.literal("打开日志目录"), btn -> {
            Util.getPlatform().openUri(AgentLogger.getLogDir().toAbsolutePath().toUri());
        }).bounds(0, 0, 100, 20).build();

        Button clearLogs = Button.builder(Component.literal("§c清空所有日志"), btn -> {
            int count = AgentLogger.clearAllLogs();
            btn.setMessage(Component.literal("§a已清除 " + count + " 个日志"));
        }).bounds(0, 0, 100, 20).build();

        configList.addEntry(new TwoButtonEntry(left, rowWidth, openLogDir, clearLogs));

        // ═══ 底部固定按钮 ═══
        int btnY = height - FOOTER_HEIGHT + 6;
        addRenderableWidget(Button.builder(Component.literal("✔ 保存"), btn -> {
            save();
            this.minecraft.setScreen(parent);
        }).bounds(width / 2 - 108, btnY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 取消"), btn -> this.minecraft.setScreen(parent))
                .bounds(width / 2 + 8, btnY, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        configList.render(g, mouseX, mouseY, delta);

        // 固定标题
        g.drawCenteredString(font, title, width / 2, 12, TITLE_COLOR);

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return configList.mouseScrolled(mouseX, mouseY, delta);
    }

    // ═══════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════

    private List<String> getModelsForCurrentProvider() {
        String provider = PROVIDERS.get(providerIndex);
        return TalkingWeaponConfig.MODELS_BY_PROVIDER.getOrDefault(provider, List.of("gpt-4o"));
    }

    private void save() {
        TalkingWeaponConfig.PROVIDER.set(PROVIDERS.get(providerIndex));
        TalkingWeaponConfig.MODEL_NAME.set(getModelsForCurrentProvider().get(modelIndex));

        trySetInt(TalkingWeaponConfig.SCREENSHOT_WIDTH,
                widthBox.getValue(), 64, 1920,
                TalkingWeaponConfig.SCREENSHOT_WIDTH.get());
        trySetInt(TalkingWeaponConfig.SCREENSHOT_HEIGHT,
                heightBox.getValue(), 36, 1080,
                TalkingWeaponConfig.SCREENSHOT_HEIGHT.get());

        trySetInt(TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS,
                structureRadiusBox.getValue(), 1, 16,
                TalkingWeaponConfig.STRUCTURE_SCAN_RADIUS.get());
        trySetInt(TalkingWeaponConfig.STRUCTURE_MAX_RESULTS,
                structureMaxResultsBox.getValue(), 1, 32,
                TalkingWeaponConfig.STRUCTURE_MAX_RESULTS.get());

        TalkingWeaponConfig.RC_ENABLE_BASE.set(rcBase);
        TalkingWeaponConfig.RC_ENABLE_SCREENSHOT.set(rcScreenshot);
        TalkingWeaponConfig.RC_ENABLE_NEARBY_ENTITIES.set(rcNearbyEntities);
        TalkingWeaponConfig.RC_ENABLE_PLAYER_ENVIRONMENT.set(rcPlayerEnvironment);
        TalkingWeaponConfig.RC_ENABLE_NEARBY_STRUCTURES.set(rcNearbyStructures);
        TalkingWeaponConfig.RC_ENABLE_NEARBY_POI.set(rcNearbyPoi);
        TalkingWeaponConfig.RC_ENABLE_LOOKING_AT.set(rcLookingAt);
        TalkingWeaponConfig.RC_ENABLE_SURROUNDING_BLOCKS.set(rcSurroundingBlocks);
        TalkingWeaponConfig.RC_ENABLE_THINKING.set(rcThinking);
        TalkingWeaponConfig.RC_ENABLE_RECENT_ACTIONS.set(rcRecentActions);

        TalkingWeaponConfig.BC_ENABLE_BASE.set(bcBase);
        TalkingWeaponConfig.BC_ENABLE_SCREENSHOT.set(bcScreenshot);
        TalkingWeaponConfig.BC_ENABLE_NEARBY_ENTITIES.set(bcNearbyEntities);
        TalkingWeaponConfig.BC_ENABLE_PLAYER_ENVIRONMENT.set(bcPlayerEnvironment);
        TalkingWeaponConfig.BC_ENABLE_NEARBY_STRUCTURES.set(bcNearbyStructures);
        TalkingWeaponConfig.BC_ENABLE_NEARBY_POI.set(bcNearbyPoi);
        TalkingWeaponConfig.BC_ENABLE_LOOKING_AT.set(bcLookingAt);
        TalkingWeaponConfig.BC_ENABLE_SURROUNDING_BLOCKS.set(bcSurroundingBlocks);
        TalkingWeaponConfig.BC_ENABLE_THINKING.set(bcThinking);

        TalkingWeaponConfig.ENTITIES_ONLY_CREATURES.set(entitiesOnlyCreatures);
        TalkingWeaponConfig.ENTITIES_LINE_OF_SIGHT.set(entitiesLineOfSight);
        TalkingWeaponConfig.ENTITIES_FUZZY_COUNT.set(entitiesFuzzyCount);

        trySetInt(TalkingWeaponConfig.THINKING_MAX_LENGTH,
                thinkingMaxLenBox.getValue(), 0, 10000,
                TalkingWeaponConfig.THINKING_MAX_LENGTH.get());

        trySetInt(TalkingWeaponConfig.TOP_K_RIGHT_CLICK,
                rcTopKBox.getValue(), 1, 20,
                TalkingWeaponConfig.TOP_K_RIGHT_CLICK.get());
        trySetInt(TalkingWeaponConfig.TOP_K_BIOME_CHANGE,
                bcTopKBox.getValue(), 1, 20,
                TalkingWeaponConfig.TOP_K_BIOME_CHANGE.get());
        trySetInt(TalkingWeaponConfig.TOP_K_CHAT,
                chatTopKBox.getValue(), 1, 20,
                TalkingWeaponConfig.TOP_K_CHAT.get());

        // 噪声阈值
        try {
            double v = Double.parseDouble(noiseThresholdBox.getValue().trim());
            TalkingWeaponConfig.NOISE_THRESHOLD.set(Math.max(0.0, Math.min(1.0, v)));
        } catch (NumberFormatException ignored) {
            TalkingWeaponConfig.NOISE_THRESHOLD.set(0.1);
        }

        TalkingWeaponConfig.ENABLE_PROACTIVE_TRIGGERS.set(enableProactiveTriggers);
        TalkingWeaponConfig.ENABLE_BIOME_DETECTION.set(enableBiomeDetection);

    }

    private void openConfigFile() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve("talking_weapon-client.toml");
        TalkingWeaponConfig.SPEC.save();
        Util.getPlatform().openUri(configFile.toUri());
    }

    private static void handleStudioActionError(Button button, String action, Exception error) {
        Path studioLogPath = StudioErrorLogger.log(action, error);
        String reason = shortErrorReason(error);
        button.setMessage(Component.literal("§c失败: " + reason));
        if (studioLogPath != null) {
            System.err.println("[TalkingWeapon/Studio] Failed to " + action + ": " + error
                    + " | detailed log: " + studioLogPath.toAbsolutePath());
        } else {
            System.err.println("[TalkingWeapon/Studio] Failed to " + action + ": " + error);
        }
        error.printStackTrace();
    }

    private static String shortErrorReason(Throwable error) {
        if (error == null) {
            return "unknown";
        }

        Throwable leaf = error;
        while (leaf.getCause() != null && leaf.getCause() != leaf) {
            leaf = leaf.getCause();
        }

        String message = leaf.getMessage();
        if (message == null || message.isBlank()) {
            message = leaf.getClass().getSimpleName();
        }

        message = message.replace('\n', ' ').replace('\r', ' ').trim().toLowerCase(Locale.ROOT);
        if (message.length() > 18) {
            message = message.substring(0, 18);
        }
        return message;
    }

    private void trySetInt(net.minecraftforge.common.ForgeConfigSpec.IntValue cfg,
            String raw, int min, int max, int fallback) {
        try {
            int v = Integer.parseInt(raw.trim());
            cfg.set(Math.max(min, Math.min(max, v)));
        } catch (NumberFormatException ignored) {
            cfg.set(fallback);
        }
    }

    private void trySetDouble(net.minecraftforge.common.ForgeConfigSpec.DoubleValue cfg,
            String raw, double min, double max, double fallback) {
        try {
            double v = Double.parseDouble(raw.trim());
            cfg.set(Math.max(min, Math.min(max, v)));
        } catch (NumberFormatException ignored) {
            cfg.set(fallback);
        }
    }

    private static Component toggleText(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§a✔ ON" : "§c✘ OFF"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ═══════════════════════════════════════════════════
    // 可滚动列表
    // ═══════════════════════════════════════════════════

    private class ConfigList extends ContainerObjectSelectionList<ConfigList.Row> {

        public ConfigList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        public int addEntry(Row entry) {
            return super.addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 400);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 4;
        }

        /** 所有条目的抽象基类 */
        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        }
    }

    // ═══════════════════════════════════════════════════
    // 行条目类型
    // ═══════════════════════════════════════════════════

    /** 纯文本标签行 */
    private class LabelEntry extends ConfigList.Row {
        private final String text;

        LabelEntry(String text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            g.drawString(font, text, left + 4, top + 6, LABEL_COLOR);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return Collections.emptyList();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return Collections.emptyList();
        }
    }

    /** 三组件行：< [中间按钮] > （用于厂商/模型选择） */
    private class WidgetRowEntry extends ConfigList.Row {
        private final String label;
        private final int rowLeft;
        private final int rowWidth;
        private final List<AbstractWidget> widgets;

        WidgetRowEntry(String label, int rowLeft, int rowWidth, AbstractWidget... ws) {
            this.label = label;
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
            this.widgets = List.of(ws);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            g.drawString(font, label, left + 4, top + 6, LABEL_COLOR);

            int btnLeft = left + rowWidth / 2;
            int btnW = rowWidth / 2;
            // < button
            widgets.get(0).setX(btnLeft);
            widgets.get(0).setY(top + 2);
            widgets.get(0).setWidth(20);
            widgets.get(0).render(g, mouseX, mouseY, delta);
            // center button
            widgets.get(1).setX(btnLeft + 24);
            widgets.get(1).setY(top + 2);
            widgets.get(1).setWidth(btnW - 48);
            widgets.get(1).render(g, mouseX, mouseY, delta);
            // > button
            widgets.get(2).setX(btnLeft + btnW - 22);
            widgets.get(2).setY(top + 2);
            widgets.get(2).setWidth(20);
            widgets.get(2).render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }

    /** 标签 + 单输入框 + 提示文本行 */
    private class InputEntry extends ConfigList.Row {
        private final String label;
        private final int rowLeft;
        private final int rowWidth;
        private final AbstractWidget input;
        private final String hint;

        InputEntry(String label, int rowLeft, int rowWidth, AbstractWidget input, String hint) {
            this.label = label;
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
            this.input = input;
            this.hint = hint;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            g.drawString(font, label, left + 4, top + 6, LABEL_COLOR);

            int btnLeft = left + rowWidth / 2;
            input.setX(btnLeft);
            input.setY(top + 2);
            input.setWidth(80);
            input.render(g, mouseX, mouseY, delta);

            if (hint != null) {
                g.drawString(font, "§7" + hint, btnLeft + 86, top + 6, LABEL_COLOR);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(input);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(input);
        }
    }

    /** 双组件行（用于截图宽高） */
    private class TwoWidgetEntry extends ConfigList.Row {
        private final String label;
        private final int rowLeft;
        private final int rowWidth;
        private final AbstractWidget w1, w2;

        TwoWidgetEntry(String label, int rowLeft, int rowWidth, AbstractWidget w1, AbstractWidget w2) {
            this.label = label;
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
            this.w1 = w1;
            this.w2 = w2;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            g.drawString(font, label, left + 4, top + 6, LABEL_COLOR);

            int btnLeft = left + rowWidth / 2;
            w1.setX(btnLeft);
            w1.setY(top + 2);
            w1.setWidth(80);
            w1.render(g, mouseX, mouseY, delta);

            g.drawString(font, "×", btnLeft + 86, top + 6, LABEL_COLOR);

            w2.setX(btnLeft + 96);
            w2.setY(top + 2);
            w2.setWidth(80);
            w2.render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(w1, w2);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(w1, w2);
        }
    }

    /** 开关行 */
    private class ToggleEntry extends ConfigList.Row {
        private final String label;
        private final int rowLeft;
        private final int rowWidth;
        private boolean value;
        private final java.util.function.Consumer<Boolean> setter;
        private final Button button;

        ToggleEntry(String label, int rowLeft, int rowWidth, boolean initial,
                java.util.function.Consumer<Boolean> setter) {
            this.label = label;
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
            this.value = initial;
            this.setter = setter;
            this.button = Button.builder(toggleText(label, value), btn -> {
                value = !value;
                setter.accept(value);
                btn.setMessage(toggleText(label, value));
            }).bounds(0, 0, 200, 20).build();
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            int btnW = Math.min(rowWidth - 8, 300);
            button.setX(left + 4);
            button.setY(top + 2);
            button.setWidth(btnW);
            button.render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    /** 单按钮居中行 */
    private class SingleWidgetEntry extends ConfigList.Row {
        private final int rowLeft;
        private final int rowWidth;
        private final AbstractWidget widget;

        SingleWidgetEntry(int rowLeft, int rowWidth, AbstractWidget widget) {
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
            this.widget = widget;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            widget.setX(left + 4);
            widget.setY(top + 2);
            widget.render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }

    /** 双按钮行（左右并排） */
    private class TwoButtonEntry extends ConfigList.Row {
        private final int rowLeft;
        private final int rowWidth;
        private final AbstractWidget btn1, btn2;

        TwoButtonEntry(int rowLeft, int rowWidth, AbstractWidget btn1, AbstractWidget btn2) {
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
            this.btn1 = btn1;
            this.btn2 = btn2;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left,
                int width, int height, int mouseX, int mouseY,
                boolean hovering, float delta) {
            int halfW = (rowWidth - 12) / 2;
            btn1.setX(left + 4);
            btn1.setY(top + 2);
            btn1.setWidth(halfW);
            btn1.render(g, mouseX, mouseY, delta);

            btn2.setX(left + 4 + halfW + 4);
            btn2.setY(top + 2);
            btn2.setWidth(halfW);
            btn2.render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(btn1, btn2);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(btn1, btn2);
        }
    }
}
