package dreamdust.talking_weapon.basic_talking_weapon;

import dreamdust.talking_weapon.basic_talking_weapon.agent.action.trigger.RightClickTrigger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentSensor;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;

/**
 * 会说话的剑：右键使用时触发 Agent 感知流程。
 * 截图、LLM 调用、回复展示等逻辑已委托给 {@link AgentSensor}。
 */
public class TalkingSword extends SwordItem {

    public TalkingSword() {
        super(Tiers.IRON, 3, -2.4f, new Item.Properties());
    }

    @Override
    public Component getName(ItemStack stack) {
        String customName = TalkingWeaponConfig.getResolvedWeaponName();
        if (customName == null || customName.isBlank()) {
            return super.getName(stack);
        }
        try {
            return Component.literal(customName);
        } catch (Throwable t) {
            System.err.println("[TalkingWeapon] Failed to build custom talking_sword name, falling back: " + t);
            return super.getName(stack);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }

        // 委托给 AgentSensor：通过 RightClickTrigger 采集上下文(含截图) → 提交 action 侧交互任务
        boolean accepted = AgentSensor.getInstance().fireTrigger(new RightClickTrigger(), player);

        return accepted
                ? InteractionResultHolder.success(player.getItemInHand(hand))
                : InteractionResultHolder.fail(player.getItemInHand(hand));
    }
}
