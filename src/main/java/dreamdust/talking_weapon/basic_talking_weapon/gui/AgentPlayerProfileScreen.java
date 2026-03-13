package dreamdust.talking_weapon.basic_talking_weapon.gui;

import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 玩家画像界面：以多行 EditBox 展示并编辑 AgentPersona.playerProfile。
 * 保存时直接写回运行时对象。
 */
public class AgentPlayerProfileScreen extends Screen {

    private static final int TITLE_COLOR = 0xFFFFD700;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int HEADER_HEIGHT = 30;
    private static final int FOOTER_HEIGHT = 36;

    private final Screen parent;
    private EditBox profileBox;

    public AgentPlayerProfileScreen(Screen parent) {
        super(Component.literal("⚔ 剑的灵魂 · 玩家画像"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AgentPersona persona = AgentRuntime.getInstance().getPersona();

        // 多行编辑框占满中间区域
        int boxW = Math.min(width - 40, 500);
        int boxH = height - HEADER_HEIGHT - FOOTER_HEIGHT - 12;
        int boxX = (width - boxW) / 2;
        int boxY = HEADER_HEIGHT + 6;

        profileBox = new EditBox(font, boxX, boxY, boxW, boxH,
                Component.literal("玩家画像"));
        profileBox.setMaxLength(4096);
        profileBox.setCanLoseFocus(true);
        profileBox.setValue(persona.getPlayerProfile() == null ? "" : persona.getPlayerProfile());
        addRenderableWidget(profileBox);

        // ── 底部按钮 ──
        int btnY = height - FOOTER_HEIGHT + 8;
        addRenderableWidget(Button.builder(Component.literal("§a✔ 保存"), btn -> {
            save();
            minecraft.setScreen(parent);
        }).bounds(width / 2 - 108, btnY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("✘ 返回"), btn -> minecraft.setScreen(parent))
                .bounds(width / 2 + 8, btnY, 100, 20).build());
    }

    private void save() {
        AgentPersona persona = AgentRuntime.getInstance().getPersona();
        persona.setPlayerProfile(profileBox.getValue());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 10, TITLE_COLOR);
        g.drawString(font, "§7LLM 对你的动态评价（可手动编辑）", (width - Math.min(width - 40, 500)) / 2 + 2,
                HEADER_HEIGHT - 2, LABEL_COLOR);
        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
