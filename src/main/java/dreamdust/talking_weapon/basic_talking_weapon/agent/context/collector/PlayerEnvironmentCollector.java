package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 玩家环境采集器：采集与玩家直接相关的环境细节。
 * <ul>
 *   <li>维度（主世界 / 下界 / 末地）</li>
 *   <li>脚下光照等级</li>
 *   <li>主手持有物品</li>
 *   <li>盔甲值</li>
 *   <li>准星目标方块</li>
 * </ul>
 * 使用原版 API，无外部依赖。
 */
public class PlayerEnvironmentCollector implements IContextCollector {

    @Override
    public String getName() {
        return "player_environment";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (!TalkingWeaponConfig.ENABLE_PLAYER_ENVIRONMENT.get()) return;
        if (player == null || level == null) return;

        // ── 维度 ──────────────────────────────────────────
        ResourceKey<Level> dimKey = level.dimension();
        builder.extra("dimension", dimKey.location().toString());

        // ── 光照等级 ──────────────────────────────────────
        BlockPos playerPos = player.blockPosition();
        int blockLight = level.getBrightness(LightLayer.BLOCK, playerPos);
        int skyLight = level.getBrightness(LightLayer.SKY, playerPos);
        builder.extra("light_level", "block=" + blockLight + ", sky=" + skyLight);

        // ── 主手物品 ──────────────────────────────────────
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            builder.extra("held_item", mainHand.getHoverName().getString());
        } else {
            builder.extra("held_item", "空手");
        }

        // ── 盔甲值 ──────────────────────────────────────
        builder.extra("armor_value", String.valueOf(player.getArmorValue()));

        // ── 经验等级 ──────────────────────────────────────
        builder.extra("xp_level", String.valueOf(player.experienceLevel));

        // ── 准星目标方块 ──────────────────────────────────
        Minecraft mc = Minecraft.getInstance();
        HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos targetPos = blockHit.getBlockPos();
            BlockState blockState = level.getBlockState(targetPos);
            String blockName = blockState.getBlock().getName().getString();
            builder.extra("looking_at_block", blockName);
        }
    }
}
