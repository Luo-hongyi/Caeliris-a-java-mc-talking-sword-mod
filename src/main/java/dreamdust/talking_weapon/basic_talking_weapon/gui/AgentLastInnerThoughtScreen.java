package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSourceType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessageKind;
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
 * 只读界面：展示最近一次 inner_monologue。
 */
public class AgentLastInnerThoughtScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int BODY_COLOR = 0xFFF2F2F2;
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 36;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private ThoughtScrollList scrollList;

    public AgentLastInnerThoughtScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 最近心声"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int rowWidth = Math.min(width - 40, 500);
        int left = (width - rowWidth) / 2;
        scrollList = new ThoughtScrollList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT, 12);
        addWidget(scrollList);

        LatestThought latest = findLatestThought();
        if (latest == null) {
            scrollList.addWrappedLines(font, left, rowWidth, "当前还没有可显示的 inner_monologue。", LABEL_COLOR);
        } else {
            scrollList.addWrappedLines(font, left, rowWidth,
                    "最近一次触发: " + abbreviate(describeTrigger(latest.entry), 96), LABEL_COLOR);
            scrollList.addWrappedLines(font, left, rowWidth,
                    "时间: " + TIME_FORMATTER.format(Instant.ofEpochMilli(latest.entry.timestamp)), LABEL_COLOR);
            scrollList.addBlank();
            scrollList.addWrappedLines(font, left, rowWidth, latest.text, BODY_COLOR);
        }

        int btnY = height - FOOTER_HEIGHT + 8;
        addRenderableWidget(Button.builder(Component.literal("返回"), btn -> minecraft.setScreen(parent))
                .bounds(width / 2 - 50, btnY, 100, 20).build());
    }

    private LatestThought findLatestThought() {
        List<StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry> entries =
                StudioRuntimeRecentInteractionsFile.readOrEmpty().entries;
        for (int i = entries.size() - 1; i >= 0; i--) {
            StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry = entries.get(i);
            List<StudioRuntimeRecentInteractionsFile.RuntimeInteractionMessageEntry> messages = entry.messages;
            for (int j = messages.size() - 1; j >= 0; j--) {
                StudioRuntimeRecentInteractionsFile.RuntimeInteractionMessageEntry message = messages.get(j);
                if (message.sourceType == TaggedContextSourceType.AGENT
                        && message.kind == InteractionMessageKind.INNER_MONOLOGUE
                        && message.text != null
                        && !message.text.isBlank()) {
                    return new LatestThought(entry, message.text.trim());
                }
            }
        }
        return null;
    }

    private static String describeTrigger(StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry) {
        if (entry == null || entry.messages == null) {
            return "(无触发)";
        }
        for (StudioRuntimeRecentInteractionsFile.RuntimeInteractionMessageEntry message : entry.messages) {
            if (message == null || message.text == null || message.text.isBlank()) {
                continue;
            }
            if ((message.sourceType == TaggedContextSourceType.AGENT
                    && message.kind == InteractionMessageKind.REACTION_CUE)
                    || message.sourceType == TaggedContextSourceType.PLAYER) {
                return message.text.trim();
            }
        }
        return entry.triggerSource == null || entry.triggerSource.isBlank() ? "(无触发)" : entry.triggerSource.trim();
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "(无提示词)";
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
        graphics.drawString(font, "§7这里只读显示最近一次被记录的 AGENT_INNER_MONOLOGUE。", (width - Math.min(width - 40, 500)) / 2,
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

    private record LatestThought(StudioRuntimeRecentInteractionsFile.RuntimeInteractionEntry entry, String text) {
    }

    private static class ThoughtScrollList extends ContainerObjectSelectionList<ThoughtScrollList.Row> {

        ThoughtScrollList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 500);
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

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        }
    }

    private static class LineRow extends ThoughtScrollList.Row {
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
