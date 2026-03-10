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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    // ── 群系变化 · 采集器开关 ──
    private boolean bcBase;
    private boolean bcScreenshot;
    private boolean bcNearbyEntities;
    private boolean bcPlayerEnvironment;
    private boolean bcNearbyStructures;

    // ── 实体探查 ──
    private boolean entitiesOnlyCreatures;
    private boolean entitiesLineOfSight;
    private boolean entitiesFuzzyCount;

    // ── Agent 行为开关 ──
    private boolean enableProactiveTriggers;
    private boolean enableBiomeDetection;

    // 可滚动列表
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
        if (this.providerIndex < 0) this.providerIndex = 0;

        String curModel = TalkingWeaponConfig.MODEL_NAME.get();
        List<String> models = getModelsForCurrentProvider();
        this.modelIndex = models.indexOf(curModel);
        if (this.modelIndex < 0) this.modelIndex = 0;

        this.rcBase = TalkingWeaponConfig.RC_ENABLE_BASE.get();
        this.rcScreenshot = TalkingWeaponConfig.RC_ENABLE_SCREENSHOT.get();
        this.rcNearbyEntities = TalkingWeaponConfig.RC_ENABLE_NEARBY_ENTITIES.get();
        this.rcPlayerEnvironment = TalkingWeaponConfig.RC_ENABLE_PLAYER_ENVIRONMENT.get();
        this.rcNearbyStructures = TalkingWeaponConfig.RC_ENABLE_NEARBY_STRUCTURES.get();

        this.bcBase = TalkingWeaponConfig.BC_ENABLE_BASE.get();
        this.bcScreenshot = TalkingWeaponConfig.BC_ENABLE_SCREENSHOT.get();
        this.bcNearbyEntities = TalkingWeaponConfig.BC_ENABLE_NEARBY_ENTITIES.get();
        this.bcPlayerEnvironment = TalkingWeaponConfig.BC_ENABLE_PLAYER_ENVIRONMENT.get();
        this.bcNearbyStructures = TalkingWeaponConfig.BC_ENABLE_NEARBY_STRUCTURES.get();

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

        // ── 截图宽高 ──
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

        configList.addEntry(new TwoWidgetEntry("半径(区块) / 数量上限", left, rowWidth, structureRadiusBox, structureMaxResultsBox));

        // ── 实体探查设置 ──
        configList.addEntry(new LabelEntry("§e▸ 实体探查设置"));
        configList.addEntry(new ToggleEntry("仅探查生物", left, rowWidth, entitiesOnlyCreatures, v -> entitiesOnlyCreatures = v));
        configList.addEntry(new ToggleEntry("视线检测（仅生物）", left, rowWidth, entitiesLineOfSight, v -> entitiesLineOfSight = v));
        configList.addEntry(new ToggleEntry("模糊数量报告", left, rowWidth, entitiesFuzzyCount, v -> entitiesFuzzyCount = v));

        // ── 右键触发 · 采集器 ──
        configList.addEntry(new LabelEntry("§e▸ 右键触发 · 采集器"));

        configList.addEntry(new ToggleEntry("基础状态采集", left, rowWidth, rcBase, v -> rcBase = v));
        configList.addEntry(new ToggleEntry("截图采集", left, rowWidth, rcScreenshot, v -> rcScreenshot = v));
        configList.addEntry(new ToggleEntry("附近实体采集", left, rowWidth, rcNearbyEntities, v -> rcNearbyEntities = v));
        configList.addEntry(new ToggleEntry("玩家环境采集", left, rowWidth, rcPlayerEnvironment, v -> rcPlayerEnvironment = v));
        configList.addEntry(new ToggleEntry("结构探查采集", left, rowWidth, rcNearbyStructures, v -> rcNearbyStructures = v));

        // ── 群系变化 · 采集器 ──
        configList.addEntry(new LabelEntry("§e▸ 群系变化 · 采集器"));

        configList.addEntry(new ToggleEntry("基础状态采集", left, rowWidth, bcBase, v -> bcBase = v));
        configList.addEntry(new ToggleEntry("截图采集", left, rowWidth, bcScreenshot, v -> bcScreenshot = v));
        configList.addEntry(new ToggleEntry("附近实体采集", left, rowWidth, bcNearbyEntities, v -> bcNearbyEntities = v));
        configList.addEntry(new ToggleEntry("玩家环境采集", left, rowWidth, bcPlayerEnvironment, v -> bcPlayerEnvironment = v));
        configList.addEntry(new ToggleEntry("结构探查采集", left, rowWidth, bcNearbyStructures, v -> bcNearbyStructures = v));

        // ── Agent 行为开关 ──
        configList.addEntry(new LabelEntry("§e▸ Agent 行为"));

        configList.addEntry(new ToggleEntry("主动触发（总开关）", left, rowWidth, enableProactiveTriggers, v -> enableProactiveTriggers = v));
        configList.addEntry(new ToggleEntry("群系变化检测", left, rowWidth, enableBiomeDetection, v -> enableBiomeDetection = v));

        // ── 打开 toml ──
        configList.addEntry(new LabelEntry(""));
        Button openToml = Button.builder(Component.literal("打开配置文件(.toml)"), btn -> openConfigFile())
                .bounds(0, 0, 210, 20).build();
        configList.addEntry(new SingleWidgetEntry(left, rowWidth, openToml));

        // ═══ 底部固定按钮 ═══
        int btnY = height - FOOTER_HEIGHT + 6;
        addRenderableWidget(Button.builder(Component.literal("✔ 保存"), btn -> {
            save();
            this.minecraft.setScreen(parent);
        }).bounds(width / 2 - 108, btnY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 取消"), btn ->
                this.minecraft.setScreen(parent)
        ).bounds(width / 2 + 8, btnY, 100, 20).build());
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
    //  辅助方法
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

        TalkingWeaponConfig.BC_ENABLE_BASE.set(bcBase);
        TalkingWeaponConfig.BC_ENABLE_SCREENSHOT.set(bcScreenshot);
        TalkingWeaponConfig.BC_ENABLE_NEARBY_ENTITIES.set(bcNearbyEntities);
        TalkingWeaponConfig.BC_ENABLE_PLAYER_ENVIRONMENT.set(bcPlayerEnvironment);
        TalkingWeaponConfig.BC_ENABLE_NEARBY_STRUCTURES.set(bcNearbyStructures);

        TalkingWeaponConfig.ENTITIES_ONLY_CREATURES.set(entitiesOnlyCreatures);
        TalkingWeaponConfig.ENTITIES_LINE_OF_SIGHT.set(entitiesLineOfSight);
        TalkingWeaponConfig.ENTITIES_FUZZY_COUNT.set(entitiesFuzzyCount);

        TalkingWeaponConfig.ENABLE_PROACTIVE_TRIGGERS.set(enableProactiveTriggers);
        TalkingWeaponConfig.ENABLE_BIOME_DETECTION.set(enableBiomeDetection);

        TalkingWeaponConfig.SPEC.save();
    }

    private void openConfigFile() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve("talking_weapon-client.toml");
        TalkingWeaponConfig.SPEC.save();
        Util.getPlatform().openUri(configFile.toUri());
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

    private static Component toggleText(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§a✔ ON" : "§c✘ OFF"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ═══════════════════════════════════════════════════
    //  可滚动列表
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
    //  行条目类型
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

        @Override public List<? extends GuiEventListener> children() { return Collections.emptyList(); }
        @Override public List<? extends NarratableEntry> narratables() { return Collections.emptyList(); }
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

        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override public List<? extends NarratableEntry> narratables() { return widgets; }
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

        @Override public List<? extends GuiEventListener> children() { return List.of(w1, w2); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(w1, w2); }
    }

    /** 开关行 */
    private class ToggleEntry extends ConfigList.Row {
        private final String label;
        private final int rowLeft;
        private final int rowWidth;
        private boolean value;
        private final java.util.function.Consumer<Boolean> setter;
        private final Button button;

        ToggleEntry(String label, int rowLeft, int rowWidth, boolean initial, java.util.function.Consumer<Boolean> setter) {
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

        @Override public List<? extends GuiEventListener> children() { return List.of(button); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(button); }
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

        @Override public List<? extends GuiEventListener> children() { return List.of(widget); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(widget); }
    }
}
