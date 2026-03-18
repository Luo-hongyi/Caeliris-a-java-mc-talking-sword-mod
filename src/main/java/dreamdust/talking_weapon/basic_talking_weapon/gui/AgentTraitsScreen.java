package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.PersonaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 性格词条编辑器：按 FocusTag 分页显示词条，支持增删改。
 * 每条词条占两行：第一行为词条文本，第二行为逗号分隔的 DetailTag 编辑框。
 */
public class AgentTraitsScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int DIM_COLOR = 0xFF888888;
    private static final int HEADER_HEIGHT = 50;
    private static final int FOOTER_HEIGHT = 40;
    /** 每行占两行高度：词条文本 + DetailTag 编辑框 */
    private static final int ROW_HEIGHT = 48;

    private static final FocusTag[] TAGS = FocusTag.values();

    private final Screen parent;
    private int tagIndex = 0;
    private Button tagLabel;

    private final List<TraitEditRow> editRows = new ArrayList<>();
    private TraitScrollList scrollList;
    private String saveError = "";

    public AgentTraitsScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 性格词条"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuildList();
    }

    private void rebuildList() {
        clearWidgets();
        editRows.clear();

        int rowWidth = Math.min(width - 40, 500);
        int left = (width - rowWidth) / 2;

        // ── Tag 选择器 ──
        Button prevTag = Button.builder(Component.literal("<"), btn -> {
            tagIndex = (tagIndex - 1 + TAGS.length) % TAGS.length;
            tagLabel.setMessage(Component.literal(TAGS[tagIndex].name()));
            rebuildList();
        }).bounds(left, HEADER_HEIGHT - 26, 20, 20).build();

        tagLabel = Button.builder(Component.literal(TAGS[tagIndex].name()), btn -> {
            tagIndex = (tagIndex + 1) % TAGS.length;
            btn.setMessage(Component.literal(TAGS[tagIndex].name()));
            rebuildList();
        }).bounds(left + 24, HEADER_HEIGHT - 26, 130, 20).build();

        Button nextTag = Button.builder(Component.literal(">"), btn -> {
            tagIndex = (tagIndex + 1) % TAGS.length;
            tagLabel.setMessage(Component.literal(TAGS[tagIndex].name()));
            rebuildList();
        }).bounds(left + 158, HEADER_HEIGHT - 26, 20, 20).build();

        addRenderableWidget(prevTag);
        addRenderableWidget(tagLabel);
        addRenderableWidget(nextTag);

        // ── 滚动列表 ──
        scrollList = new TraitScrollList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT, ROW_HEIGHT);
        addWidget(scrollList);

        AgentPersona persona = AgentRuntime.getInstance().getPersona();
        for (PersonaEntry trait : persona.getPersonaEntriesByFocus(TAGS[tagIndex])) {
            TraitEditRow row = new TraitEditRow(trait, left, rowWidth);
            editRows.add(row);
            scrollList.addEntry(row);
        }

        // ── 底部按钮 ──
        int btnY = height - FOOTER_HEIGHT + 8;
        int bx = (width - 290) / 2;

        addRenderableWidget(Button.builder(Component.literal("§a+ 新增"), btn -> {
            if (!save()) {
                return;
            }
            AgentPersona p = AgentRuntime.getInstance().getPersona();
            p.addPersonaEntry(new PersonaEntry("新词条", TAGS[tagIndex]));
            rebuildList();
        }).bounds(bx, btnY, 86, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§a✔ 保存"), btn -> {
            if (save()) {
                minecraft.setScreen(parent);
            }
        }).bounds(bx + 94, btnY, 86, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 返回"), btn -> minecraft.setScreen(parent))
                .bounds(bx + 188, btnY, 86, 20).build());
    }

    private boolean save() {
        saveError = "";
        try {
            for (TraitEditRow row : editRows) {
                if (!row.deleted) {
                    row.trait.setDescription(row.traitBox.getValue());
                    row.trait.setDetailTagsFromString(row.detailTagsBox.getValue());
                }
            }
            AgentPersona persona = AgentRuntime.getInstance().getPersona();
            for (TraitEditRow row : editRows) {
                if (row.deleted)
                    persona.removePersonaEntry(row.trait);
            }
            PersonaStorage.saveEntries(persona.getPersonaEntries());
            persona.reloadStudioPersonaEntries();
            return true;
        } catch (IllegalArgumentException e) {
            saveError = e.getMessage();
            return false;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 10, TITLE_COLOR);
        g.drawString(font, "§7分类:", (width - Math.min(width - 40, 500)) / 2 + 4, HEADER_HEIGHT - 28, LABEL_COLOR);
        if (!saveError.isBlank()) {
            g.drawCenteredString(font, Component.literal(saveError), width / 2, height - FOOTER_HEIGHT - 12, 0xFFFF6B6B);
        }
        scrollList.render(g, mouseX, mouseY, delta);
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

    private static class TraitScrollList extends ContainerObjectSelectionList<TraitScrollList.Row> {

        TraitScrollList(Minecraft mc, int w, int h, int top, int bottom, int itemH) {
            super(mc, w, h, top, bottom, itemH);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        public int addEntry(Row e) {
            return super.addEntry(e);
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 500);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 4;
        }

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 词条编辑行（两行显示）
    // ═══════════════════════════════════════════════════════════════

    private class TraitEditRow extends TraitScrollList.Row {
        final PersonaEntry trait;
        final EditBox traitBox;
        final EditBox detailTagsBox;
        boolean deleted = false;
        private final Button deleteBtn;
        private final int rowWidth;

        TraitEditRow(PersonaEntry trait, int rowLeft, int rowWidth) {
            this.trait = trait;
            this.rowWidth = rowWidth;

            // 第一行：词条文本
            traitBox = new EditBox(minecraft.font, 0, 0, rowWidth - 36, 18, Component.literal(""));
            traitBox.setMaxLength(128);
            traitBox.setCanLoseFocus(true);
            traitBox.setValue(trait.getDescription());

            // 第二行：DetailTag 编辑框
            detailTagsBox = new EditBox(minecraft.font, 0, 0, rowWidth - 36, 18, Component.literal(""));
            detailTagsBox.setMaxLength(256);
            detailTagsBox.setCanLoseFocus(true);
            detailTagsBox.setValue(trait.getDetailTagsAsString());

            deleteBtn = Button.builder(Component.literal("§c✗"), btn -> {
                deleted = true;
                btn.active = false;
            }).bounds(0, 0, 24, 18).build();
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            if (deleted) {
                g.drawString(minecraft.font, "§7§m" + trait.getDescription(), left + 4, top + 7, 0xFF666666);
                return;
            }
            // 行一：词条文本 + 删除按钮
            traitBox.setX(left + 4);
            traitBox.setY(top + 2);
            traitBox.setWidth(rowWidth - 36);
            traitBox.render(g, mx, my, delta);

            deleteBtn.setX(left + rowWidth - 28);
            deleteBtn.setY(top + 2);
            deleteBtn.render(g, mx, my, delta);

            // 行二：DetailTag 标签提示 + 编辑框
            g.drawString(minecraft.font, "§8tags:", left + 4, top + 24 + 3, DIM_COLOR);
            detailTagsBox.setX(left + 36);
            detailTagsBox.setY(top + 24);
            detailTagsBox.setWidth(rowWidth - 68);
            detailTagsBox.render(g, mx, my, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return deleted ? Collections.emptyList() : List.of(traitBox, detailTagsBox, deleteBtn);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return deleted ? Collections.emptyList() : List.of(traitBox, detailTagsBox, deleteBtn);
        }
    }
}
