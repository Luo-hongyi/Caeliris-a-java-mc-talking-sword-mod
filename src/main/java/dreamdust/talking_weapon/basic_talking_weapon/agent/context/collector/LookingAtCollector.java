package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 注视目标采集器：获取玩家准心（crosshair）当前对准的方块或实体。
 * <p>
 * 使用 {@link FocusTag#COMMON} 赋予最高权重，强行打断注意力偏好。
 * 若准心方块、普通实体则输出详细信息。
 * 若准心为展示框，则解析其内含的物品并判定为 ITEM 还是 BLOCK。
 */
public class LookingAtCollector implements IContextCollector {

    @Override
    public String getName() {
        return "looking_at";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null)
            return;

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

            String detailPrefix = DetailTag.ENTITY;
            String detailValue = name;

            // 处理展示框特殊情况：获取内部物品
            if (crosshairEntity instanceof ItemFrame itemFrame) {
                ItemStack framedItem = itemFrame.getItem();
                if (!framedItem.isEmpty()) {
                    String itemName = framedItem.getHoverName().getString();
                    sb.append(" [展示框内物品: ").append(itemName).append("]");
                    detailValue = itemName;
                    if (framedItem.getItem() instanceof BlockItem) {
                        detailPrefix = DetailTag.BLOCK;
                    } else {
                        detailPrefix = DetailTag.ITEM;
                    }
                }
            }

            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.COMMON)
                    .detail(detailPrefix, detailValue)
                    .description(sb.toString())
                    .importance(1.0f)
                    .build());
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
            sb.append(" (坐标 ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ())
                    .append(")");
            sb.append(" (距离 ").append(String.format("%.1f", dist)).append("格)");

            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.COMMON)
                    .detail(DetailTag.BLOCK, blockName)
                    .description(sb.toString())
                    .importance(1.0f)
                    .build());
            return;
        }

        // ── 什么也没看 ──
        builder.extra("looking_at", "正在看: 天空/远方(无目标)");
    }
}
