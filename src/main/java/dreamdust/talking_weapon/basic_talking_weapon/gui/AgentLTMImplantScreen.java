package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
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
 * 全局植入记忆编辑器：显示和增删改 SourceType.IMPLANTED 记忆。
 */
public class AgentLTMImplantScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int DIM_COLOR = 0xFF888888;
    private static final int HEADER_HEIGHT = 45;
    private static final int FOOTER_HEIGHT = 40;
    private static final int ROW_HEIGHT = 65; // 三行高：内容 + FocusTags + DetailTags

    private final Screen parent;
    private final List<MemoryEditRow> editRows = new ArrayList<>();
    private MemoryScrollList scrollList;

    public AgentLTMImplantScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 出厂设定(植入记忆)"));
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

        scrollList = new MemoryScrollList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT, ROW_HEIGHT);
        addWidget(scrollList);

        // 提取现有的 Implanted
        List<LTMEntry> allImplanted = LTMStorage.loadGlobalImplantedMemories();

        for (LTMEntry ltm : allImplanted) {
            MemoryEditRow row = new MemoryEditRow(ltm, left, rowWidth);
            editRows.add(row);
            scrollList.addEntry(row);
        }

        // ── 底部按钮 ──
        int btnY = height - FOOTER_HEIGHT + 8;
        int bx = (width - 290) / 2;

        addRenderableWidget(Button.builder(Component.literal("§a+ 新增"), btn -> {
            LTMEntry newEntry = LTMEntry.builder()
                    .sourceType(LTMEntry.SourceType.IMPLANTED)
                    .description("新植入的设定描述...")
                    .focus(FocusTag.COMMON)
                    .build();
            // 直接加进列表，让用户修改后再 save
            MemoryEditRow row = new MemoryEditRow(newEntry, left, rowWidth);
            editRows.add(row);
            scrollList.addEntry(row);
        }).bounds(bx, btnY, 86, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§a✔ 保存"), btn -> {
            save();
            minecraft.setScreen(parent);
        }).bounds(bx + 94, btnY, 86, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 返回"), btn -> minecraft.setScreen(parent))
                .bounds(bx + 188, btnY, 86, 20).build());
    }

    private void save() {
        // 重建整个 Implanted 列表
        List<LTMEntry> finalList = new ArrayList<>();
        
        for (MemoryEditRow row : editRows) {
            if (!row.deleted) {
                LTMEntry.Builder b = LTMEntry.builder()
                        .sourceType(LTMEntry.SourceType.IMPLANTED)
                        .description(row.descBox.getValue())
                        .importance(1.0f); // 默认最高权重
                
                String fStr = row.focusTagsBox.getValue();
                for (String token : fStr.split(",")) {
                    token = token.trim();
                    if (!token.isEmpty()) {
                        try { b.focus(FocusTag.valueOf(token.toUpperCase())); } catch (Exception ignored) {}
                    }
                }
                
                String dStr = row.detailTagsBox.getValue();
                for (String token : dStr.split(",")) {
                    token = token.trim();
                    if (!token.isEmpty()) {
                        String[] parts = token.split(":", 2);
                        if (parts.length == 2) b.detail(parts[0], parts[1]);
                    }
                }
                finalList.add(b.build());
            }
        }
        
        // 我们利用 LTMStorage 覆盖写能力，直接写空文件并重新循环 append
        try {
            java.nio.file.Files.deleteIfExists(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("talking_weapon/implanted_memories.json"));
        } catch (Exception ignored) {}
        
        for (LTMEntry entry : finalList) {
            LTMStorage.saveGlobalImplantedMemory(entry);
        }

        // 直接内存热加载重洗
        AgentRuntime.getInstance().getMemory().reloadImplantedMemories();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 10, TITLE_COLOR);
        g.drawCenteredString(font, "§7全地图通用的植入记忆（修改后保存立即生效）", width / 2, 25, DIM_COLOR);
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

    private static class MemoryScrollList extends ContainerObjectSelectionList<MemoryScrollList.Row> {
        MemoryScrollList(Minecraft mc, int w, int h, int top, int bottom, int itemH) {
            super(mc, w, h, top, bottom, itemH);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        public int addEntry(Row e) { return super.addEntry(e); }

        @Override public int getRowWidth() { return Math.min(width - 40, 500); }
        @Override protected int getScrollbarPosition() { return width / 2 + getRowWidth() / 2 + 4; }

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {}
    }

    // ═══════════════════════════════════════════════════════════════

    private class MemoryEditRow extends MemoryScrollList.Row {
        final EditBox descBox;
        final EditBox focusTagsBox;
        final EditBox detailTagsBox;
        boolean deleted = false;
        private final Button deleteBtn;
        private final int rowWidth;

        MemoryEditRow(LTMEntry entry, int rowLeft, int rowWidth) {
            this.rowWidth = rowWidth;

            descBox = new EditBox(minecraft.font, 0, 0, rowWidth - 36, 18, Component.literal(""));
            descBox.setMaxLength(256);
            descBox.setValue(entry.getDescription());

            StringBuilder fb = new StringBuilder();
            entry.getFocusTags().forEach(f -> fb.append(f.name()).append(", "));
            String fbs = fb.toString();
            if (fbs.endsWith(", ")) fbs = fbs.substring(0, fbs.length() - 2);

            focusTagsBox = new EditBox(minecraft.font, 0, 0, rowWidth - 68, 18, Component.literal(""));
            focusTagsBox.setMaxLength(128);
            focusTagsBox.setValue(fbs);

            StringBuilder db = new StringBuilder();
            entry.getDetailTags().forEach(d -> db.append(d.toString()).append(", "));
            String dbs = db.toString();
            if (dbs.endsWith(", ")) dbs = dbs.substring(0, dbs.length() - 2);

            detailTagsBox = new EditBox(minecraft.font, 0, 0, rowWidth - 68, 18, Component.literal(""));
            detailTagsBox.setMaxLength(256);
            detailTagsBox.setValue(dbs);

            deleteBtn = Button.builder(Component.literal("§c✗"), btn -> {
                deleted = true;
                btn.active = false;
            }).bounds(0, 0, 24, 18).build();
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            if (deleted) {
                g.drawString(minecraft.font, "§m已删除的植入记忆", left + 4, top + 7, 0xFF666666);
                return;
            }

            // 行一: Desc
            descBox.setX(left + 4);
            descBox.setY(top + 2);
            descBox.setWidth(rowWidth - 36);
            descBox.render(g, mx, my, delta);

            deleteBtn.setX(left + rowWidth - 28);
            deleteBtn.setY(top + 2);
            deleteBtn.render(g, mx, my, delta);

            // 行二: Focus
            g.drawString(minecraft.font, "§8focus:", left + 4, top + 24 + 5, DIM_COLOR);
            focusTagsBox.setX(left + 40);
            focusTagsBox.setY(top + 24);
            focusTagsBox.setWidth(rowWidth - 44);
            focusTagsBox.render(g, mx, my, delta);

            // 行三: Detail
            g.drawString(minecraft.font, "§8detail:", left + 4, top + 46 + 5, DIM_COLOR);
            detailTagsBox.setX(left + 40);
            detailTagsBox.setY(top + 46);
            detailTagsBox.setWidth(rowWidth - 44);
            detailTagsBox.render(g, mx, my, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return deleted ? Collections.emptyList() : List.of(descBox, focusTagsBox, detailTagsBox, deleteBtn);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return deleted ? Collections.emptyList() : List.of(descBox, focusTagsBox, detailTagsBox, deleteBtn);
        }
    }
}
