package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentStateStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState.PrimaryEmotion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState.RecallPreset;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioErrorLogger;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeActionTimelineFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeEpisodeTimelineFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspaceExporter;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePaths;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioWorkspacePublisher;
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

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.nio.file.Path;

/**
 * Agent 主概览界面：显示并编辑当前 AgentState 和 AgentPersona 的核心字段。
 * <p>
 * 所有字段均可编辑（测试版）。底部提供导航按钮跳转子界面。
 */
public class AgentStateScreen extends Screen {

    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 68;

    private final Screen parent;

    private static final PrimaryEmotion[] EMOTIONS = PrimaryEmotion.values();
    private static final RecallPreset[] RECALL_PRESETS = RecallPreset.values();
    private static final FocusTag[] DESIRE_TAGS = FocusTag.values();
    private int emotionIndex;

    // 编辑框引用（供 save() 读取）
    private EditBox intensityBox;
    private EditBox playerAddressBox;
    private EditBox familiarityBox;
    private EditBox favorabilityBox;

    // 注意力权重编辑框
    private EditBox wCombat, wExplore, wLabor, wCreation, wCreature, wSocial;
    private EditBox wLandscape, wHazard, wStatus, wConsumable, wBlock, wTool, wCommon;
    private boolean crosshairBonusEnabled;

    // 思维特性编辑引用
    private EditBox wanderingMindBox;
    private EditBox nostalgiaBox;
    private EditBox customMainCandidatesPerCueBox;
    private EditBox customSandboxCuesPerMemoryBox;
    private EditBox customConsciousMemoryCapBox;
    private EditBox customRetentionMemoryCapBox;
    private EditBox desireDescBox;
    private int recallPresetIndex;
    private int desireTagIndex; // 0 = 无渴望，1…n = FocusTag
    private boolean implantedMemorySafetyNetEnabled;
    private boolean fullRawActionLogEnabled;

    private Button emotionLabel;
    private Button recallPresetLabel;
    private Button desireTagLabel;
    private Button implantedMemorySafetyNetButton;
    private Button fullRawActionLogButton;
    private Button crosshairBonusButton;
    private StateScrollList scrollList;

    public AgentStateScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 状态概览"));
        this.parent = parent;

        AgentState state = AgentRuntime.getInstance().getState();
        this.emotionIndex = emotionIndexOf(state.getPrimaryEmotion());
        this.recallPresetIndex = recallPresetIndexOf(state.getRecallPreset());
        this.implantedMemorySafetyNetEnabled = state.isImplantedMemorySafetyNetEnabled();
        this.fullRawActionLogEnabled = state.isFullRawActionLogEnabled();
        this.crosshairBonusEnabled = TalkingWeaponConfig.LOOKING_AT_ATTENTION_BONUS_ENABLED.get();
        // desireTagIndex: 0=无渴望, 1..n=FocusTag
        FocusTag desire = state.getCurrentDesireTag();
        this.desireTagIndex = desire == null ? 0 : desireTagIndexOf(desire) + 1;
    }

    private static int emotionIndexOf(PrimaryEmotion e) {
        for (int i = 0; i < EMOTIONS.length; i++)
            if (EMOTIONS[i] == e)
                return i;
        return 0;
    }

    private static int recallPresetIndexOf(RecallPreset preset) {
        for (int i = 0; i < RECALL_PRESETS.length; i++)
            if (RECALL_PRESETS[i] == preset)
                return i;
        return RecallPreset.HYBRID.ordinal();
    }

    private static int desireTagIndexOf(FocusTag tag) {
        for (int i = 0; i < DESIRE_TAGS.length; i++)
            if (DESIRE_TAGS[i] == tag)
                return i;
        return 0;
    }

    @Override
    protected void init() {
        AgentState state = AgentRuntime.getInstance().getState();
        AgentPersona persona = AgentRuntime.getInstance().getPersona();

        int rowWidth = Math.min(width - 40, 420);
        int left = (width - rowWidth) / 2;

        scrollList = new StateScrollList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT, 26);
        addWidget(scrollList);

        // ── 情绪状态 ──
        scrollList.addEntry(new LabelRow("§b▸ 情绪状态"));

        // 情绪循环选择
        Button prevEmotion = Button.builder(Component.literal("<"), btn -> {
            emotionIndex = (emotionIndex - 1 + EMOTIONS.length) % EMOTIONS.length;
            emotionLabel.setMessage(Component.literal(EMOTIONS[emotionIndex].name()));
        }).bounds(0, 0, 20, 20).build();

        emotionLabel = Button.builder(Component.literal(EMOTIONS[emotionIndex].name()), btn -> {
            emotionIndex = (emotionIndex + 1) % EMOTIONS.length;
            btn.setMessage(Component.literal(EMOTIONS[emotionIndex].name()));
        }).bounds(0, 0, 120, 20).build();

        Button nextEmotion = Button.builder(Component.literal(">"), btn -> {
            emotionIndex = (emotionIndex + 1) % EMOTIONS.length;
            emotionLabel.setMessage(Component.literal(EMOTIONS[emotionIndex].name()));
        }).bounds(0, 0, 20, 20).build();

        scrollList.addEntry(new ThreeWidgetRow("情绪类型", left, rowWidth, prevEmotion, emotionLabel, nextEmotion));

        intensityBox = editBox("强度", String.valueOf(state.getEmotionIntensity()), 3);
        scrollList.addEntry(new BoxRow("情绪强度 (0-100)", left, rowWidth, intensityBox));

        // ── 关系数值 ──
        scrollList.addEntry(new LabelRow("§b▸ 关系数值"));

        playerAddressBox = editBox("称呼", state.getPlayerAddress(), 16);
        scrollList.addEntry(new BoxRow("对玩家称呼", left, rowWidth, playerAddressBox));

        familiarityBox = editBox("熟悉度", String.valueOf(state.getFamiliarity()), 8);
        scrollList.addEntry(new BoxRow("熟悉度 (累计次数)", left, rowWidth, familiarityBox));

        favorabilityBox = editBox("好感度", String.valueOf(persona.getFavorability()), 4);
        scrollList.addEntry(new BoxRow("好感度 (-100 ~ 100)", left, rowWidth, favorabilityBox));

        // ── 注意力权重（可编辑） ──
        scrollList.addEntry(new LabelRow("§b▸ 注意力权重 (0.0 ~ 10.0)"));

        wCombat = weightBox(TalkingWeaponConfig.WEIGHT_COMBAT.get());
        scrollList.addEntry(new BoxRow("COMBAT 战斗", left, rowWidth, wCombat));
        wExplore = weightBox(TalkingWeaponConfig.WEIGHT_EXPLORE.get());
        scrollList.addEntry(new BoxRow("EXPLORE 探索", left, rowWidth, wExplore));
        wLabor = weightBox(TalkingWeaponConfig.WEIGHT_LABOR.get());
        scrollList.addEntry(new BoxRow("LABOR 劳作", left, rowWidth, wLabor));
        wCreation = weightBox(TalkingWeaponConfig.WEIGHT_CREATION.get());
        scrollList.addEntry(new BoxRow("CREATION 创造", left, rowWidth, wCreation));
        wCreature = weightBox(TalkingWeaponConfig.WEIGHT_CREATURE.get());
        scrollList.addEntry(new BoxRow("CREATURE 生物", left, rowWidth, wCreature));
        wSocial = weightBox(TalkingWeaponConfig.WEIGHT_SOCIAL.get());
        scrollList.addEntry(new BoxRow("SOCIAL 社交", left, rowWidth, wSocial));
        wLandscape = weightBox(TalkingWeaponConfig.WEIGHT_LANDSCAPE.get());
        scrollList.addEntry(new BoxRow("LANDSCAPE 风景", left, rowWidth, wLandscape));
        wHazard = weightBox(TalkingWeaponConfig.WEIGHT_HAZARD.get());
        scrollList.addEntry(new BoxRow("HAZARD 危险", left, rowWidth, wHazard));
        wStatus = weightBox(TalkingWeaponConfig.WEIGHT_STATUS.get());
        scrollList.addEntry(new BoxRow("STATUS 状态", left, rowWidth, wStatus));
        wConsumable = weightBox(TalkingWeaponConfig.WEIGHT_CONSUMABLE.get());
        scrollList.addEntry(new BoxRow("CONSUMABLE 消耗品", left, rowWidth, wConsumable));
        wBlock = weightBox(TalkingWeaponConfig.WEIGHT_BLOCK.get());
        scrollList.addEntry(new BoxRow("BLOCK 方块", left, rowWidth, wBlock));
        wTool = weightBox(TalkingWeaponConfig.WEIGHT_TOOL.get());
        scrollList.addEntry(new BoxRow("TOOL 工具", left, rowWidth, wTool));
        wCommon = weightBox(TalkingWeaponConfig.WEIGHT_COMMON.get());
        scrollList.addEntry(new BoxRow("COMMON 通用", left, rowWidth, wCommon));
        crosshairBonusButton = Button.builder(
                Component.literal(crosshairBonusEnabled ? "开启" : "关闭"),
                btn -> {
                    crosshairBonusEnabled = !crosshairBonusEnabled;
                    btn.setMessage(Component.literal(crosshairBonusEnabled ? "开启" : "关闭"));
                }).bounds(0, 0, 100, 20).build();
        scrollList.addEntry(new ButtonRow("准心额外加分", left, rowWidth, crosshairBonusButton));

        // ── 思维特性 ──
        scrollList.addEntry(new LabelRow("§b▸ 思维特性"));

        wanderingMindBox = editBox("随想值", String.format("%.2f", state.getWanderingMind()), 4);
        scrollList.addEntry(new BoxRow("随想值 (0.00~1.00)", left, rowWidth, wanderingMindBox));

        nostalgiaBox = editBox("忆旧值", String.format("%.2f", state.getNostalgia()), 4);
        scrollList.addEntry(new BoxRow("忆旧值（预留） (0.00~2.00)", left, rowWidth, nostalgiaBox));

        // 召回预设选择器
        Button prevRecallPreset = Button.builder(Component.literal("<"), btn -> {
            recallPresetIndex = (recallPresetIndex - 1 + RECALL_PRESETS.length) % RECALL_PRESETS.length;
            recallPresetLabel.setMessage(Component.literal(RECALL_PRESETS[recallPresetIndex].name()));
        }).bounds(0, 0, 20, 20).build();
        recallPresetLabel = Button.builder(Component.literal(RECALL_PRESETS[recallPresetIndex].name()), btn -> {
            recallPresetIndex = (recallPresetIndex + 1) % RECALL_PRESETS.length;
            btn.setMessage(Component.literal(RECALL_PRESETS[recallPresetIndex].name()));
        }).bounds(0, 0, 120, 20).build();
        Button nextRecallPreset = Button.builder(Component.literal(">"), btn -> {
            recallPresetIndex = (recallPresetIndex + 1) % RECALL_PRESETS.length;
            recallPresetLabel.setMessage(Component.literal(RECALL_PRESETS[recallPresetIndex].name()));
        }).bounds(0, 0, 20, 20).build();
        scrollList.addEntry(new ThreeWidgetRow("召回预设", left, rowWidth, prevRecallPreset, recallPresetLabel, nextRecallPreset));

        AgentState.RecallTuning recallTuning = state.getRecallTuning();
        scrollList.addEntry(new LabelRow("§8  CUSTOM 调参（仅在自定义预设生效）"));

        customMainCandidatesPerCueBox = editBox(
                "mainRecallBudget",
                String.valueOf(recallTuning.getMainCandidatesPerCue()),
                2);
        scrollList.addEntry(new BoxRow("Main Recall Budget", left, rowWidth, customMainCandidatesPerCueBox));

        customSandboxCuesPerMemoryBox = editBox(
                "sandboxCueBudget",
                String.valueOf(recallTuning.getSandboxCuesPerMemory()),
                2);
        scrollList.addEntry(new BoxRow("Sandbox Cue Budget", left, rowWidth, customSandboxCuesPerMemoryBox));

        customConsciousMemoryCapBox = editBox(
                "consciousMemoryCap",
                String.valueOf(recallTuning.getConsciousMemoryCap()),
                2);
        scrollList.addEntry(new BoxRow("Conscious Memory Cap", left, rowWidth, customConsciousMemoryCapBox));

        customRetentionMemoryCapBox = editBox(
                "retentionMemoryCap",
                String.valueOf(recallTuning.getRetentionMemoryCap()),
                2);
        scrollList.addEntry(new BoxRow("Retention Memory Cap", left, rowWidth, customRetentionMemoryCapBox));

        implantedMemorySafetyNetButton = Button.builder(
                Component.literal(implantedMemorySafetyNetEnabled ? "开启" : "关闭"),
                btn -> {
                    implantedMemorySafetyNetEnabled = !implantedMemorySafetyNetEnabled;
                    btn.setMessage(Component.literal(implantedMemorySafetyNetEnabled ? "开启" : "关闭"));
                }).bounds(0, 0, 100, 20).build();
        scrollList.addEntry(new ButtonRow("植入记忆保底", left, rowWidth, implantedMemorySafetyNetButton));
        scrollList.addEntry(new LabelRow("§8  开启后，若本轮正常召回未命中任何无 occurTime 的植入记忆，会额外做一次轻量补捞"));

        scrollList.addEntry(new LabelRow("§b▸ 调试日志"));
        fullRawActionLogButton = Button.builder(
                Component.literal(fullRawActionLogEnabled ? "开启" : "关闭"),
                btn -> {
                    fullRawActionLogEnabled = !fullRawActionLogEnabled;
                    btn.setMessage(Component.literal(fullRawActionLogEnabled ? "开启" : "关闭"));
                }).bounds(0, 0, 100, 20).build();
        scrollList.addEntry(new ButtonRow("全量原始动作日志", left, rowWidth, fullRawActionLogButton));
        scrollList.addEntry(new LabelRow("§8  开启后，交互触发会额外写出 action archive dump，默认建议关闭"));

        // 渴望分类选择器（0 = 无渴望, 1..n = FocusTag）
        String desireLabelStr = desireTagIndex == 0 ? "(无)" : DESIRE_TAGS[desireTagIndex - 1].name();
        Button prevDesire = Button.builder(Component.literal("<"), btn -> {
            desireTagIndex = (desireTagIndex - 1 + DESIRE_TAGS.length + 1) % (DESIRE_TAGS.length + 1);
            desireTagLabel.setMessage(
                    Component.literal(desireTagIndex == 0 ? "(无)" : DESIRE_TAGS[desireTagIndex - 1].name()));
        }).bounds(0, 0, 20, 20).build();
        desireTagLabel = Button.builder(Component.literal(desireLabelStr), btn -> {
            desireTagIndex = (desireTagIndex + 1) % (DESIRE_TAGS.length + 1);
            btn.setMessage(Component.literal(desireTagIndex == 0 ? "(无)" : DESIRE_TAGS[desireTagIndex - 1].name()));
        }).bounds(0, 0, 120, 20).build();
        Button nextDesire = Button.builder(Component.literal(">"), btn -> {
            desireTagIndex = (desireTagIndex + 1) % (DESIRE_TAGS.length + 1);
            desireTagLabel.setMessage(
                    Component.literal(desireTagIndex == 0 ? "(无)" : DESIRE_TAGS[desireTagIndex - 1].name()));
        }).bounds(0, 0, 20, 20).build();
        scrollList.addEntry(new ThreeWidgetRow("渴望分类", left, rowWidth, prevDesire, desireTagLabel, nextDesire));

        desireDescBox = editBox("渴望描述", state.getDesireDescription(), 64);
        scrollList.addEntry(new BoxRow("渴望描述", left, rowWidth, desireDescBox));

        scrollList.addEntry(new LabelRow("§b▸ Studio 工作区"));

        Button connectStudioWorkspace = Button.builder(Component.literal("建立连接"), btn -> {
            try {
                StudioWorkspacePaths.ensureWorkspaceRootExists();
                Util.getPlatform().openUri(StudioWorkspaceExporter.getWorkspaceRoot().toUri());
                btn.setMessage(Component.literal("§a已打开工作区"));
            } catch (Exception e) {
                handleStudioActionError(btn, "connect studio workspace", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button exportStudioWorkspace = Button.builder(Component.literal("手动导出"), btn -> {
            try {
                StudioWorkspaceExporter.ExportResult result = StudioWorkspaceExporter.exportWorkspace();
                btn.setMessage(Component.literal("§a已导出 " + result.fileCount + " 项"));
            } catch (Exception e) {
                handleStudioActionError(btn, "export studio workspace", e);
            }
        }).bounds(0, 0, 100, 20).build();

        Button publishStudioWorkspace = Button.builder(Component.literal("手动读取"), btn -> {
            try {
                StudioWorkspacePublisher.PublishResult result = StudioWorkspacePublisher.publishWorkspace();
                btn.setMessage(Component.literal("§a已读取 " + result.publishedFiles.size() + " 项"));
            } catch (Exception e) {
                handleStudioActionError(btn, "publish studio workspace", e);
            }
        }).bounds(0, 0, 100, 20).build();

        scrollList.addEntry(new ThreeButtonActionRow(left, rowWidth, connectStudioWorkspace, exportStudioWorkspace, publishStudioWorkspace));

        Button compactSegmentStore = Button.builder(Component.literal("执行"), btn -> {
            try {
                boolean compacted = AgentRuntime.getInstance().getMemory().compactDiscardedSourceMaterial();
                StudioRuntimeActionTimelineFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());
                StudioRuntimeEpisodeTimelineFile.writeCurrentSnapshot(AgentRuntime.getInstance().getMemory());
                btn.setMessage(Component.literal(compacted ? "§a已 Compact" : "§e无待处理"));
            } catch (Exception e) {
                handleStudioActionError(btn, "compact fixed segment store", e);
            }
        }).bounds(0, 0, 100, 20).build();
        scrollList.addEntry(new ButtonRow("手动 Compact 段文件", left, rowWidth, compactSegmentStore));
        scrollList.addEntry(new LabelRow("§8  将 pending discard windows 物理压进当前 fixed segment 持久化，并立即回写 runtime snapshot"));

        // ── 底部导航按钮 ──
        int btnW = 92;
        int gap = 6;
        int totalW = btnW * 3 + gap * 2;
        int bx = (width - totalW) / 2;
        int firstRowY = height - FOOTER_HEIGHT + 8;
        int secondRowY = firstRowY + 24;

        addRenderableWidget(Button.builder(Component.literal("§6玩家画像 →"), btn -> {
            save();
            minecraft.setScreen(new AgentPlayerProfileScreen(this));
        }).bounds(bx, firstRowY, btnW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§6性格词条 →"), btn -> {
            save();
            minecraft.setScreen(new AgentTraitsScreen(this));
        }).bounds(bx + (btnW + gap) * 1, firstRowY, btnW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§6地点注册 →"), btn -> {
            save();
            minecraft.setScreen(new AgentLocationRegistryScreen(this));
        }).bounds(bx + (btnW + gap) * 2, firstRowY, btnW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§6最近心声 →"), btn -> {
            save();
            minecraft.setScreen(new AgentLastInnerThoughtScreen(this));
        }).bounds(bx, secondRowY, btnW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§6触发记录 →"), btn -> {
            save();
            minecraft.setScreen(new AgentRecentInteractionScreen(this));
        }).bounds(bx + (btnW + gap) * 1, secondRowY, btnW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§a✔ 保存"), btn -> {
            save();
            minecraft.setScreen(parent);
        }).bounds(bx + (btnW + gap) * 2, secondRowY, btnW, 20).build());
    }

    private EditBox editBox(String hint, String value, int maxLen) {
        EditBox box = new EditBox(font, 0, 0, 100, 20, Component.literal(hint));
        box.setValue(value);
        box.setMaxLength(maxLen);
        box.setCanLoseFocus(true);
        return box;
    }

    private EditBox weightBox(double value) {
        EditBox box = new EditBox(font, 0, 0, 70, 20, Component.literal(""));
        box.setValue(String.format("%.2f", value));
        box.setMaxLength(5);
        box.setCanLoseFocus(true);
        return box;
    }

    private void save() {
        AgentState state = AgentRuntime.getInstance().getState();
        AgentPersona persona = AgentRuntime.getInstance().getPersona();
        state.setPrimaryEmotion(EMOTIONS[emotionIndex]);
        state.setPlayerAddress(playerAddressBox.getValue().trim());
        try {
            state.setEmotionIntensity(Integer.parseInt(intensityBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        try {
            state.setFamiliarity(Integer.parseInt(familiarityBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        try {
            persona.setFavorability(Integer.parseInt(favorabilityBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        try {
            state.setWanderingMind(Float.parseFloat(wanderingMindBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        try {
            state.setNostalgia(Float.parseFloat(nostalgiaBox.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        state.setRecallPreset(RECALL_PRESETS[recallPresetIndex]);
        AgentState.RecallTuning currentRecallTuning = state.getRecallTuning();
        state.setRecallTuning(new AgentState.RecallTuning(
                parseIntOrDefault(customMainCandidatesPerCueBox, currentRecallTuning.getMainCandidatesPerCue()),
                parseIntOrDefault(customSandboxCuesPerMemoryBox, currentRecallTuning.getSandboxCuesPerMemory()),
                parseIntOrDefault(customConsciousMemoryCapBox, currentRecallTuning.getConsciousMemoryCap()),
                parseIntOrDefault(customRetentionMemoryCapBox, currentRecallTuning.getRetentionMemoryCap())));
        state.setImplantedMemorySafetyNetEnabled(implantedMemorySafetyNetEnabled);
        state.setFullRawActionLogEnabled(fullRawActionLogEnabled);
        state.setCurrentDesireTag(desireTagIndex == 0 ? null : DESIRE_TAGS[desireTagIndex - 1]);
        state.setDesireDescription(desireDescBox.getValue().trim());

        // 注意力权重→写入配置
        setWeight(TalkingWeaponConfig.WEIGHT_COMBAT, wCombat);
        setWeight(TalkingWeaponConfig.WEIGHT_EXPLORE, wExplore);
        setWeight(TalkingWeaponConfig.WEIGHT_LABOR, wLabor);
        setWeight(TalkingWeaponConfig.WEIGHT_CREATION, wCreation);
        setWeight(TalkingWeaponConfig.WEIGHT_CREATURE, wCreature);
        setWeight(TalkingWeaponConfig.WEIGHT_SOCIAL, wSocial);
        setWeight(TalkingWeaponConfig.WEIGHT_LANDSCAPE, wLandscape);
        setWeight(TalkingWeaponConfig.WEIGHT_HAZARD, wHazard);
        setWeight(TalkingWeaponConfig.WEIGHT_STATUS, wStatus);
        setWeight(TalkingWeaponConfig.WEIGHT_CONSUMABLE, wConsumable);
        setWeight(TalkingWeaponConfig.WEIGHT_BLOCK, wBlock);
        setWeight(TalkingWeaponConfig.WEIGHT_TOOL, wTool);
        setWeight(TalkingWeaponConfig.WEIGHT_COMMON, wCommon);
        TalkingWeaponConfig.LOOKING_AT_ATTENTION_BONUS_ENABLED.set(crosshairBonusEnabled);
        TalkingWeaponConfig.SPEC.save();
        AgentStateStorage.flushSaveRuntimeState(state, persona);

        // 当用户编辑了配置文本并回到控制面板点击"保存"时，立即热重载记忆池
        AgentRuntime.getInstance().getMemory().reloadImplantedMemories();
    }

    private static int parseIntOrDefault(EditBox box, int fallback) {
        if (box == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void setWeight(net.minecraftforge.common.ForgeConfigSpec.DoubleValue cfg, EditBox box) {
        try {
            double v = Double.parseDouble(box.getValue().trim());
            cfg.set(Math.max(0.0, Math.min(10.0, v)));
        } catch (NumberFormatException ignored) {
        }
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        scrollList.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, title, width / 2, 10, TITLE_COLOR);
        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return scrollList.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // 滚动列表
    // ═══════════════════════════════════════════════════════════════

    private static class StateScrollList extends ContainerObjectSelectionList<StateScrollList.Row> {

        StateScrollList(Minecraft mc, int w, int h, int top, int bottom, int itemH) {
            super(mc, w, h, top, bottom, itemH);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        public int addEntry(Row e) {
            return super.addEntry(e);
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 420);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 4;
        }

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        }
    }

    // ── 行类型 ─────────────────────────────────────────────────────

    /** 纯文本标签行 */
    private class LabelRow extends StateScrollList.Row {
        private final String text;

        LabelRow(String text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
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

    /** 标签 + EditBox 行（右侧对齐） */
    private class BoxRow extends StateScrollList.Row {
        private final String label;
        private final int rowWidth;
        private final EditBox box;

        BoxRow(String label, int rowLeft, int rowWidth, EditBox box) {
            this.label = label;
            this.rowWidth = rowWidth;
            this.box = box;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            g.drawString(font, label, left + 4, top + 6, LABEL_COLOR);
            box.setX(left + rowWidth - 106);
            box.setY(top + 2);
            box.setWidth(100);
            box.render(g, mx, my, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(box);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(box);
        }
    }

    /** "< [center] >" 三按钮选择行 */
    private class ThreeWidgetRow extends StateScrollList.Row {
        private final String label;
        private final int rowWidth;
        private final AbstractWidget w1, wMid, w2;

        ThreeWidgetRow(String label, int rowLeft, int rowWidth,
                AbstractWidget w1, AbstractWidget wMid, AbstractWidget w2) {
            this.label = label;
            this.rowWidth = rowWidth;
            this.w1 = w1;
            this.wMid = wMid;
            this.w2 = w2;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            g.drawString(font, label, left + 4, top + 6, LABEL_COLOR);
            int rx = left + rowWidth - 168;
            w1.setX(rx);
            w1.setY(top + 2);
            w1.setWidth(20);
            wMid.setX(rx + 24);
            wMid.setY(top + 2);
            wMid.setWidth(120);
            w2.setX(rx + 148);
            w2.setY(top + 2);
            w2.setWidth(20);
            w1.render(g, mx, my, delta);
            wMid.render(g, mx, my, delta);
            w2.render(g, mx, my, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(w1, wMid, w2);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(w1, wMid, w2);
        }
    }

    /** 标签 + 单按钮行 */
    private class ButtonRow extends StateScrollList.Row {
        private final String label;
        private final int rowWidth;
        private final AbstractWidget widget;

        ButtonRow(String label, int rowLeft, int rowWidth, AbstractWidget widget) {
            this.label = label;
            this.rowWidth = rowWidth;
            this.widget = widget;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            g.drawString(font, label, left + 4, top + 6, LABEL_COLOR);
            widget.setX(left + rowWidth - 106);
            widget.setY(top + 2);
            widget.setWidth(100);
            widget.render(g, mx, my, delta);
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

    private class ThreeButtonActionRow extends StateScrollList.Row {
        private final int rowWidth;
        private final AbstractWidget w1, w2, w3;

        ThreeButtonActionRow(int rowLeft, int rowWidth, AbstractWidget w1, AbstractWidget w2, AbstractWidget w3) {
            this.rowWidth = rowWidth;
            this.w1 = w1;
            this.w2 = w2;
            this.w3 = w3;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            int gap = 4;
            int thirdW = (rowWidth - 8 - gap * 2) / 3;

            w1.setX(left + 4);
            w1.setY(top + 2);
            w1.setWidth(thirdW);
            w1.render(g, mx, my, delta);

            w2.setX(left + 4 + thirdW + gap);
            w2.setY(top + 2);
            w2.setWidth(thirdW);
            w2.render(g, mx, my, delta);

            w3.setX(left + 4 + (thirdW + gap) * 2);
            w3.setY(top + 2);
            w3.setWidth(thirdW);
            w3.render(g, mx, my, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(w1, w2, w3);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(w1, w2, w3);
        }
    }
}
