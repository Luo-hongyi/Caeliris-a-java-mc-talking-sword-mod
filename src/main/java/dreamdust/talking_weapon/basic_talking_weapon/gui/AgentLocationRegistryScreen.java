package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地点注册管理界面：以可编辑列表展示并修改 {@link LocationRegistry} 中的所有地标。
 * 底部"添加当前坐标"按钮自动填入玩家位置。
 */
public class AgentLocationRegistryScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int HEADER_HEIGHT = 42;
    private static final int FOOTER_HEIGHT = 40;

    private final Screen parent;
    private LocationScrollList scrollList;
    private final List<LocationRow> rows = new ArrayList<>();
    private int rowLeft, rowWidth;

    public AgentLocationRegistryScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 地点注册"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rowWidth = Math.min(width - 40, 580);
        rowLeft = (width - rowWidth) / 2;
        rebuildList();
    }

    private void rebuildList() {
        rows.clear();
        clearWidgets();

        scrollList = new LocationScrollList(minecraft, width, height, HEADER_HEIGHT, height - FOOTER_HEIGHT, 30);
        addWidget(scrollList);

        // 列标题
        scrollList.addEntry(new HeaderRow());

        // 已有地点
        LocationRegistry reg = LocationRegistry.getInstance();
        for (String name : reg.allNames()) {
            LocationRegistry.LocationEntry entry = reg.get(name);
            if (entry == null)
                continue;
            LocationRow row = new LocationRow(name, entry);
            rows.add(row);
            scrollList.addEntry(row);
        }

        // ── 底部按钮 ──
        int btnY = height - FOOTER_HEIGHT + 8;
        int bx = (width - 292) / 2;

        addRenderableWidget(Button.builder(Component.literal("§e+ 当前坐标"), btn -> {
            Player player = Minecraft.getInstance().player;
            if (player == null)
                return;
            BlockPos pos = player.blockPosition();
            String dim = player.level().dimension().location().toString();
            LocationRegistry.LocationEntry e = new LocationRegistry.LocationEntry(
                    pos.getX(), pos.getY(), pos.getZ(), dim, "");
            LocationRow row = new LocationRow("新地点" + (rows.size() + 1), e);
            rows.add(row);
            scrollList.addEntry(row);
        }).bounds(bx, btnY, 94, 20).build());

        addRenderableWidget(Button.builder(Component.literal("§a✔ 保存"), btn -> {
            save();
            minecraft.setScreen(parent);
        }).bounds(bx + 102, btnY, 86, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 返回"), btn -> minecraft.setScreen(parent))
                .bounds(bx + 196, btnY, 86, 20).build());
    }

    private void save() {
        LocationRegistry reg = LocationRegistry.getInstance();
        for (String name : new ArrayList<>(reg.allNames())) {
            reg.remove(name);
        }
        for (LocationRow row : rows) {
            if (!row.deleted) {
                String name = row.nameBox.getValue().trim().toLowerCase();
                if (name.isBlank())
                    continue;
                LocationRegistry.LocationEntry entry = new LocationRegistry.LocationEntry();
                try {
                    entry.x = Integer.parseInt(row.xBox.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
                try {
                    entry.y = Integer.parseInt(row.yBox.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
                try {
                    entry.z = Integer.parseInt(row.zBox.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
                entry.dim = row.dimBox.getValue().trim();
                entry.note = row.noteBox.getValue().trim();
                reg.put(name, entry);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 10, TITLE_COLOR);
        g.drawString(font, "§7保存后立即写入 location_registry.json",
                rowLeft + 2, HEADER_HEIGHT - 14, LABEL_COLOR);
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

    private static class LocationScrollList extends ContainerObjectSelectionList<LocationScrollList.Row> {

        LocationScrollList(Minecraft mc, int w, int h, int top, int bottom, int itemH) {
            super(mc, w, h, top, bottom, itemH);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        public int addEntry(Row e) {
            return super.addEntry(e);
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 580);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 4;
        }

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        }
    }

    // ── 列标题行 ──

    private class HeaderRow extends LocationScrollList.Row {
        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            g.drawString(font, "§7名称", left + 4, top + 9, LABEL_COLOR);
            g.drawString(font, "§7X", left + 100, top + 9, LABEL_COLOR);
            g.drawString(font, "§7Y", left + 152, top + 9, LABEL_COLOR);
            g.drawString(font, "§7Z", left + 204, top + 9, LABEL_COLOR);
            g.drawString(font, "§7维度", left + 258, top + 9, LABEL_COLOR);
            g.drawString(font, "§7备注", left + 368, top + 9, LABEL_COLOR);
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

    // ── 地点编辑行 ──

    private class LocationRow extends LocationScrollList.Row {
        final EditBox nameBox, xBox, yBox, zBox, dimBox, noteBox;
        Button deleteBtn;
        boolean deleted = false;

        LocationRow(String name, LocationRegistry.LocationEntry entry) {
            nameBox = box(90, name, 16);
            xBox = box(46, String.valueOf(entry.x), 7);
            yBox = box(46, String.valueOf(entry.y), 7);
            zBox = box(46, String.valueOf(entry.z), 7);
            dimBox = box(102, entry.dim != null ? entry.dim : "", 64);
            noteBox = box(Math.max(60, rowWidth - 504), entry.note != null ? entry.note : "", 64);
            deleteBtn = Button.builder(Component.literal("§c✗"), btn -> {
                deleted = true;
                btn.active = false;
            }).bounds(0, 0, 24, 20).build();
        }

        private EditBox box(int w, String val, int maxLen) {
            EditBox b = new EditBox(font, 0, 0, w, 20, Component.empty());
            b.setValue(val);
            b.setMaxLength(maxLen);
            b.setCanLoseFocus(true);
            return b;
        }

        @Override
        public void render(GuiGraphics g, int idx, int top, int left, int w, int h,
                int mx, int my, boolean hov, float delta) {
            if (deleted) {
                g.drawString(font, "§7§m" + nameBox.getValue(), left + 4, top + 9, 0xFF888888);
                return;
            }
            int y = top + 5;
            nameBox.setX(left + 4);
            nameBox.setY(y);
            nameBox.render(g, mx, my, delta);
            xBox.setX(left + 98);
            xBox.setY(y);
            xBox.render(g, mx, my, delta);
            yBox.setX(left + 148);
            yBox.setY(y);
            yBox.render(g, mx, my, delta);
            zBox.setX(left + 198);
            zBox.setY(y);
            zBox.render(g, mx, my, delta);
            dimBox.setX(left + 248);
            dimBox.setY(y);
            dimBox.render(g, mx, my, delta);
            int noteW = Math.max(60, rowWidth - 504);
            noteBox.setX(left + 354);
            noteBox.setY(y);
            noteBox.setWidth(noteW);
            noteBox.render(g, mx, my, delta);
            deleteBtn.setX(left + rowWidth - 26);
            deleteBtn.setY(y);
            deleteBtn.render(g, mx, my, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return deleted ? Collections.emptyList()
                    : List.of(nameBox, xBox, yBox, zBox, dimBox, noteBox, deleteBtn);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return deleted ? Collections.emptyList()
                    : List.of(nameBox, xBox, yBox, zBox, dimBox, noteBox, deleteBtn);
        }
    }
}
