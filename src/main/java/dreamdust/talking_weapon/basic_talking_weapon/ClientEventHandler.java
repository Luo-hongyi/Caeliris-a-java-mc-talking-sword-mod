package dreamdust.talking_weapon.basic_talking_weapon;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentSensor;
import dreamdust.talking_weapon.basic_talking_weapon.gui.AgentStateScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端输入事件处理：
 * <ul>
 * <li>持剑 + 左键打断请求（原有逻辑）</li>
 * <li>按 G 键打开 Agent 状态概览界面</li>
 * </ul>
 */
public class ClientEventHandler {

    /** G 键绑定 — 打开 Agent 状态界面 */
    public static final KeyMapping KEY_AGENT_STATUS = new KeyMapping(
            "key.talking_weapon.agent_status", // i18n key（可在 en_us.json 中翻译）
            GLFW.GLFW_KEY_G, // 默认按键 G
            "key.categories.talking_weapon" // 分组
    );

    // ── 按键注册（在 FML mod event bus 上监听） ──

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(KEY_AGENT_STATUS);
    }

    // ── Tick 检测按键 ──

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null)
            return;

        if (KEY_AGENT_STATUS.consumeClick()) {
            mc.setScreen(new AgentStateScreen(null));
        }
    }

    // ── 左键打断（原有逻辑） ──

    @SubscribeEvent
    public void onAttackInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        Player player = mc.player;

        if (!isHoldingTalkingSword(player))
            return;

        AgentSensor.getInstance().triggerCancel(player);
    }

    @SubscribeEvent
    public void onUseInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }

        Player player = mc.player;
        if (!isHoldingTalkingSword(player)) {
            return;
        }

        // 持剑右键时统一走 right trigger，不让方块 / 实体先吃掉交互。
        event.setCanceled(true);
        AgentSensor.getInstance().fireTrigger(new dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.RightClickTrigger(), player);
    }

    private static boolean isHoldingTalkingSword(Player player) {
        return player.getMainHandItem().getItem() instanceof TalkingSword
                || player.getOffhandItem().getItem() instanceof TalkingSword;
    }
}
