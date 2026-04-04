package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeRecentInteractionsFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 只读界面：极简显示最近的触发记录、台词和动作类型摘要。
 * <p>
 * 数据源直接读取 Studio workspace 中的 runtime 交互文件，不依赖 STMPool 是否已被清空。
 */
public class AgentRecentInteractionScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int BODY_COLOR = 0xFFF2F2F2;
    private static final int DIM_COLOR = 0xFF8E8E8E;
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 36;
    private static final int LINE_HEIGHT = 12;
    private static final int MAX_TURNS = 18;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private InteractionScrollList scrollList;

    public AgentRecentInteractionScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 触发记录"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int rowWidth = Math.min(width - 40, 520);
        int left = (width - rowWidth) / 2;
        scrollList = new InteractionScrollList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT,
                LINE_HEIGHT);
        addWidget(scrollList);

        List<StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry> entries =
                StudioRuntimeRecentInteractionsFile.readOrEmpty().entries;
        if (entries.isEmpty()) {
            scrollList.addWrappedLines(font, left, rowWidth, "暂无触发记录。", DIM_COLOR);
        } else {
            int start = Math.max(0, entries.size() - MAX_TURNS);
            for (int i = entries.size() - 1; i >= start; i--) {
                StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry = entries.get(i);
                scrollList.addWrappedLines(font, left, rowWidth, buildTriggerLine(entry), LABEL_COLOR);
                scrollList.addWrappedLines(font, left, rowWidth, buildSpeechLine(entry), BODY_COLOR);
                scrollList.addWrappedLines(font, left, rowWidth, buildActionLine(entry), DIM_COLOR);
                scrollList.addBlank();
            }
        }

        int btnY = height - FOOTER_HEIGHT + 8;
        addRenderableWidget(Button.builder(Component.literal("返回"), btn -> minecraft.setScreen(parent))
                .bounds(width / 2 - 50, btnY, 100, 20).build());
    }

    private static String buildTriggerLine(StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry) {
        String time = TIME_FORMATTER.format(Instant.ofEpochMilli(entry.timestamp));
        String imageMark = entry.hasImage ? " [+image]" : "";
        String prompt = entry.userPrompt == null || entry.userPrompt.isBlank() ? "(无提示词)" : entry.userPrompt.trim();
        return time + "  触发: " + prompt + imageMark;
    }

    private static String buildSpeechLine(StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry) {
        for (StudioRuntimeRecentInteractionsFile.RuntimeInteractionOutputEntry output : entry.outputs) {
            if ("speech".equals(output.type) && output.text != null && !output.text.isBlank()) {
                return "说话: " + output.text.trim();
            }
        }
        return "说话: (沉默)";
    }

    private static String buildActionLine(StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry) {
        List<String> shortTypes = new ArrayList<>();
        for (String actionType : entry.actionTypes) {
            String shortLabel = shortActionType(actionType);
            if (!shortTypes.contains(shortLabel)) {
                shortTypes.add(shortLabel);
            }
        }
        if (shortTypes.isEmpty()) {
            return "动作: 无";
        }
        return "动作: " + String.join(" / ", shortTypes);
    }

    private static String shortActionType(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return "未知";
        }
        return switch (actionType.toLowerCase()) {
            case "speech" -> "说话";
            case "inner_monologue" -> "心声";
            case "emotion_update" -> "情绪";
            default -> abbreviate(actionType, 8);
        };
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "(无)";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        scrollList.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, TITLE_COLOR);
        graphics.drawString(font, "§7这里按 user prompt、说话内容、动作类型极简显示最近回合。", (width - Math.min(width - 40, 520)) / 2,
                HEADER_HEIGHT - 12, LABEL_COLOR);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return scrollList.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class InteractionScrollList extends ContainerObjectSelectionList<InteractionScrollList.Row> {

        InteractionScrollList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 520);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 4;
        }

        void addWrappedLines(Font font, int left, int rowWidth, String text, int color) {
            int wrapWidth = Math.max(40, rowWidth - 8);
            List<FormattedCharSequence> lines = font.split(Component.literal(text == null ? "" : text), wrapWidth);
            if (lines.isEmpty()) {
                addEntry(new LineRow(FormattedCharSequence.forward("", net.minecraft.network.chat.Style.EMPTY), color));
                return;
            }
            for (FormattedCharSequence line : lines) {
                addEntry(new LineRow(line, color));
            }
        }

        void addBlank() {
            addEntry(new LineRow(FormattedCharSequence.forward("", net.minecraft.network.chat.Style.EMPTY), BODY_COLOR));
        }

        public int addEntry(Row row) {
            return super.addEntry(row);
        }

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        }
    }

    private static class LineRow extends InteractionScrollList.Row {
        private final FormattedCharSequence text;
        private final int color;

        private LineRow(FormattedCharSequence text, int color) {
            this.text = text;
            this.color = color;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                int mouseY, boolean hovered, float partialTick) {
            graphics.drawString(Minecraft.getInstance().font, text, left + 4, top + 2, color);
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
}
