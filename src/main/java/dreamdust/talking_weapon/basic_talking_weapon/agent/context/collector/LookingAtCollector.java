package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 注视目标采集器：获取玩家准心（crosshair）当前对准的方块或实体，
 * 写入 {@code extras["looking_at"]}。
 * <p>
 * 信息包括：
 * <ul>
 *   <li><b>方块</b> — 名称、坐标、距离</li>
 *   <li><b>实体</b> — 名称、距离；若为生物还包含生命值</li>
 *   <li><b>无</b> — 正在看天空 / 虚空</li>
 * </ul>
 * <p>
 * 使用 {@link Minecraft#hitResult} 和 {@link Minecraft#crosshairPickEntity} 原版 API。
 */
public class LookingAtCollector implements IContextCollector {

    @Override
    public String getName() {
        return "looking_at";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null) return;

        Minecraft mc = Minecraft.getInstance();

        // ── 优先检测实体（准心对着的实体） ──
        Entity crosshairEntity = mc.crosshairPickEntity;
        if (crosshairEntity != null) {
            StringBuilder sb = new StringBuilder();
            String name = crosshairEntity.getType().getDescription().getString();
            double dist = player.distanceTo(crosshairEntity);

            sb.append("正在看: ").append(name);
            sb.append(" (距离 ").append(String.format("%.1f", dist)).append("格)");

            if (crosshairEntity instanceof LivingEntity living) {
                sb.append(" [生命: ")
                  .append(String.format("%.0f", living.getHealth()))
                  .append("/")
                  .append(String.format("%.0f", living.getMaxHealth()))
                  .append("]");
            }

            // 如果实体有自定义名称
            if (crosshairEntity.hasCustomName() && crosshairEntity.getCustomName() != null) {
                sb.append(" 名称: \"").append(crosshairEntity.getCustomName().getString()).append("\"");
            }

            builder.extra("looking_at", sb.toString());
            return;
        }

        // ── 检测方块 ──
        HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos pos = blockHit.getBlockPos();
            BlockState blockState = level.getBlockState(pos);
            String blockName = blockState.getBlock().getName().getString();

            double dist = Math.sqrt(player.blockPosition().distSqr(pos));

            StringBuilder sb = new StringBuilder();
            sb.append("正在看: ").append(blockName);
            sb.append(" (坐标 ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append(")");
            sb.append(" (距离 ").append(String.format("%.1f", dist)).append("格)");

            builder.extra("looking_at", sb.toString());
            return;
        }

        // ── 什么也没看 ──
        builder.extra("looking_at", "正在看: 天空/远方(无目标)");
    }
}
