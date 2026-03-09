package dreamdust.talking_weapon.basic_talking_weapon;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentSensor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端输入事件处理：
 * 当玩家手持会说话的剑，且 Agent 正在思考时，
 * 按下左键（攻击键）将立即打断请求。
 * 逻辑委托给 {@link AgentSensor#triggerCancel(Player)}。
 */
public class ClientEventHandler {

    @SubscribeEvent
    public void onAttackInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Player player = mc.player;

        // 只在手持 talking_sword 时响应左键打断
        boolean holdingSword =
                player.getMainHandItem().getItem() instanceof TalkingSword ||
                player.getOffhandItem().getItem() instanceof TalkingSword;

        if (!holdingSword) return;

        // 委托给 AgentSensor 取消当前任务
        AgentSensor.getInstance().triggerCancel(player);
    }
}
