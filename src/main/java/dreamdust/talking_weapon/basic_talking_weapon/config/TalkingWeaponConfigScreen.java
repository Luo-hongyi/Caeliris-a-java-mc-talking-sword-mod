package dreamdust.talking_weapon.basic_talking_weapon.config;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * 游戏内配置界面：在 Mod 列表点击「配置」时弹出。
 * 无需额外依赖，使用原版 Screen / EditBox / Button。
 */
public class TalkingWeaponConfigScreen extends Screen {

    private final Screen parent;

    // 输入框
    private EditBox widthBox;
    private EditBox heightBox;

    // 厂商选择
    private int providerIndex;
    // 模型循环选择
    private int modelIndex;

    // ── 采集器开关状态（运行时，保存时写入 Config） ──
    private boolean enableBase;
    private boolean enableScreenshot;
    private boolean enableNearbyEntities;
    private boolean enablePlayerEnvironment;

    // ── Agent 行为开关 ──
    private boolean enableProactiveTriggers;
    private boolean enableBiomeDetection;

    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LEFT = 50;
    private static final int COL2 = 250;

    private static final java.util.List<String> PROVIDERS = TalkingWeaponConfig.AVAILABLE_PROVIDERS;

    public TalkingWeaponConfigScreen(Screen parent) {
        super(Component.literal("⚔ 会说话的武器 · 设置"));
        this.parent = parent;

        String curProvider = TalkingWeaponConfig.PROVIDER.get();
        this.providerIndex = PROVIDERS.indexOf(curProvider);
        if (this.providerIndex < 0) this.providerIndex = 0;

        String curModel = TalkingWeaponConfig.MODEL_NAME.get();
        java.util.List<String> models = getModelsForCurrentProvider();
        this.modelIndex = models.indexOf(curModel);
        if (this.modelIndex < 0) this.modelIndex = 0;

        // 读取当前 config 值
        this.enableBase = TalkingWeaponConfig.ENABLE_BASE_COLLECTOR.get();
        this.enableScreenshot = TalkingWeaponConfig.ENABLE_SCREENSHOT.get();
        this.enableNearbyEntities = TalkingWeaponConfig.ENABLE_NEARBY_ENTITIES.get();
        this.enablePlayerEnvironment = TalkingWeaponConfig.ENABLE_PLAYER_ENVIRONMENT.get();
        this.enableProactiveTriggers = TalkingWeaponConfig.ENABLE_PROACTIVE_TRIGGERS.get();
        this.enableBiomeDetection = TalkingWeaponConfig.ENABLE_BIOME_DETECTION.get();
    }

    @Override
    protected void init() {
        int y = 40;
        int gap = 24;

        // ═══ 厂商选择 ═══
        final int providerY = y + 12;
        addRenderableWidget(Button.builder(
                Component.literal("< "), btn -> {
                    providerIndex = (providerIndex - 1 + PROVIDERS.size()) % PROVIDERS.size();
                    modelIndex = 0;
                    updateProviderButton();
                    updateModelButton();
                }).bounds(LEFT, providerY, 20, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(PROVIDERS.get(providerIndex)), btn -> {
                    providerIndex = (providerIndex + 1) % PROVIDERS.size();
                    modelIndex = 0;
                    btn.setMessage(Component.literal(PROVIDERS.get(providerIndex)));
                    updateModelButton();
                }).bounds(LEFT + 24, providerY, 170, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(" >"), btn -> {
                    providerIndex = (providerIndex + 1) % PROVIDERS.size();
                    modelIndex = 0;
                    updateProviderButton();
                    updateModelButton();
                }).bounds(LEFT + 198, providerY, 20, 20).build());
        y += gap + 6;

        // ═══ 模型选择 ═══
        final int modelY = y + 12;
        addRenderableWidget(Button.builder(
                Component.literal("< "), btn -> {
                    java.util.List<String> models = getModelsForCurrentProvider();
                    modelIndex = (modelIndex - 1 + models.size()) % models.size();
                    updateModelButton();
                }).bounds(LEFT, modelY, 20, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(getModelsForCurrentProvider().get(modelIndex)), btn -> {
                    java.util.List<String> models = getModelsForCurrentProvider();
                    modelIndex = (modelIndex + 1) % models.size();
                    btn.setMessage(Component.literal(models.get(modelIndex)));
                }).bounds(LEFT + 24, modelY, 170, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(" >"), btn -> {
                    java.util.List<String> models = getModelsForCurrentProvider();
                    modelIndex = (modelIndex + 1) % models.size();
                    updateModelButton();
                }).bounds(LEFT + 198, modelY, 20, 20).build());
        y += gap + 6;

        // ═══ 截图宽高 ═══
        widthBox = newBox(LEFT, y + 12, 80, 20, "截图宽度");
        widthBox.setValue(String.valueOf(TalkingWeaponConfig.SCREENSHOT_WIDTH.get()));
        widthBox.setMaxLength(4);
        addRenderableWidget(widthBox);

        heightBox = newBox(LEFT + 110, y + 12, 80, 20, "截图高度");
        heightBox.setValue(String.valueOf(TalkingWeaponConfig.SCREENSHOT_HEIGHT.get()));
        heightBox.setMaxLength(4);
        addRenderableWidget(heightBox);
        y += gap + 10;

        // ═══════════════════════════════════════════════════
        //  采集器开关（左列）  /  Agent 行为开关（右列）
        // ═══════════════════════════════════════════════════

        // —— 左列：采集器开关 ——
        int toggleY = y + 12;

        addRenderableWidget(Button.builder(toggleLabel("基础状态", enableBase), btn -> {
            enableBase = !enableBase;
            btn.setMessage(toggleLabel("基础状态", enableBase));
        }).bounds(LEFT, toggleY, 180, 20).build());
        toggleY += gap;

        addRenderableWidget(Button.builder(toggleLabel("截图", enableScreenshot), btn -> {
            enableScreenshot = !enableScreenshot;
            btn.setMessage(toggleLabel("截图", enableScreenshot));
        }).bounds(LEFT, toggleY, 180, 20).build());
        toggleY += gap;

        addRenderableWidget(Button.builder(toggleLabel("附近实体", enableNearbyEntities), btn -> {
            enableNearbyEntities = !enableNearbyEntities;
            btn.setMessage(toggleLabel("附近实体", enableNearbyEntities));
        }).bounds(LEFT, toggleY, 180, 20).build());
        toggleY += gap;

        addRenderableWidget(Button.builder(toggleLabel("玩家环境", enablePlayerEnvironment), btn -> {
            enablePlayerEnvironment = !enablePlayerEnvironment;
            btn.setMessage(toggleLabel("玩家环境", enablePlayerEnvironment));
        }).bounds(LEFT, toggleY, 180, 20).build());

        // —— 右列：Agent 行为开关 ——
        int rightY = y + 12;

        addRenderableWidget(Button.builder(toggleLabel("主动触发", enableProactiveTriggers), btn -> {
            enableProactiveTriggers = !enableProactiveTriggers;
            btn.setMessage(toggleLabel("主动触发", enableProactiveTriggers));
        }).bounds(COL2, rightY, 180, 20).build());
        rightY += gap;

        addRenderableWidget(Button.builder(toggleLabel("群系检测", enableBiomeDetection), btn -> {
            enableBiomeDetection = !enableBiomeDetection;
            btn.setMessage(toggleLabel("群系检测", enableBiomeDetection));
        }).bounds(COL2, rightY, 180, 20).build());

        y = toggleY + gap + 4;

        // ═══ 底部按钮 ═══
        addRenderableWidget(Button.builder(Component.literal("打开配置文件(.toml)"), btn ->
                openConfigFile()
        ).bounds(LEFT, y, 210, 20).build());
        y += gap + 4;

        addRenderableWidget(Button.builder(Component.literal("✔ 保存"), btn -> {
            save();
            this.minecraft.setScreen(parent);
        }).bounds(LEFT, y, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 取消"), btn ->
                this.minecraft.setScreen(parent)
        ).bounds(LEFT + 110, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);

        g.drawCenteredString(font, title, width / 2, 14, TITLE_COLOR);

        int y = 40;
        int gap = 24;
        drawLabel(g, "厂商选择", LEFT, y);
        y += gap + 6;
        drawLabel(g, "模型选择", LEFT, y);
        y += gap + 6;
        drawLabel(g, "截图宽度 × 高度（像素）", LEFT, y);
        y += gap + 10;

        // 分栏标题
        drawLabel(g, "§e▸ 采集器开关", LEFT, y);
        drawLabel(g, "§e▸ Agent 行为", COL2, y);

        super.render(g, mouseX, mouseY, delta);
    }

    private void drawLabel(GuiGraphics g, String text, int x, int y) {
        g.drawString(font, text, x, y, LABEL_COLOR);
    }

    private EditBox newBox(int x, int y, int w, int h, String hint) {
        EditBox box = new EditBox(font, x, y, w, h, Component.literal(hint));
        box.setCanLoseFocus(true);
        return box;
    }

    private void updateModelButton() {
        java.util.List<String> models = getModelsForCurrentProvider();
        renderables.stream()
                .filter(r -> r instanceof Button)
                .map(r -> (Button) r)
                .skip(3)
                .filter(b -> models.contains(b.getMessage().getString()))
                .findFirst()
                .ifPresent(b -> b.setMessage(Component.literal(models.get(modelIndex))));
    }

    private void updateProviderButton() {
        renderables.stream()
                .filter(r -> r instanceof Button)
                .map(r -> (Button) r)
                .filter(b -> PROVIDERS.contains(b.getMessage().getString()))
                .findFirst()
                .ifPresent(b -> b.setMessage(Component.literal(PROVIDERS.get(providerIndex))));
    }

    private java.util.List<String> getModelsForCurrentProvider() {
        String provider = PROVIDERS.get(providerIndex);
        return TalkingWeaponConfig.MODELS_BY_PROVIDER.getOrDefault(provider, java.util.List.of("gpt-4o"));
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

        // 采集器开关
        TalkingWeaponConfig.ENABLE_BASE_COLLECTOR.set(enableBase);
        TalkingWeaponConfig.ENABLE_SCREENSHOT.set(enableScreenshot);
        TalkingWeaponConfig.ENABLE_NEARBY_ENTITIES.set(enableNearbyEntities);
        TalkingWeaponConfig.ENABLE_PLAYER_ENVIRONMENT.set(enablePlayerEnvironment);

        // Agent 行为开关
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

    /**
     * 生成 "名称: §a✔ ON" 或 "名称: §c✘ OFF" 的 toggle 文本。
     */
    private static Component toggleLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§a✔ ON" : "§c✘ OFF"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
