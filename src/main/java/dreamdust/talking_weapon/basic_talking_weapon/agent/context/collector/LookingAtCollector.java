package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
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
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 注视目标采集器：获取玩家准心（crosshair）当前对准的方块或实体。
 * <p>
 * 该采集器会产出带 {@link FocusTag#COMMON} 的普通上下文条目，
 * 并额外标记为 `crosshairTarget`，供注意力层按独立开关施加温和的准心加分。
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
        TaggedContextSource environmentSource = TaggedContextSource.environment();

        Minecraft mc = Minecraft.getInstance();

        // ── 优先检测实体（准心对着的实体） ──
        Entity crosshairEntity = mc.crosshairPickEntity;
        if (crosshairEntity != null) {
            StringBuilder sb = new StringBuilder();
            String name = crosshairEntity.getType().getDescription().getString();
            double dist = player.distanceTo(crosshairEntity);

            sb.append("当前准心目标: ").append(name);
            sb.append(" (距离").append(dreamdust.talking_weapon.basic_talking_weapon.agent.context.DistanceFuzzer.fuzzify(dist)).append(")");

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

            String detailPrefix = DetailTag.NATIVE_SPECIFIC;
            String detailValue = resolveEntityId(crosshairEntity);

            // 处理展示框特殊情况：获取内部物品
            if (crosshairEntity instanceof ItemFrame itemFrame) {
                ItemStack framedItem = itemFrame.getItem();
                if (!framedItem.isEmpty()) {
                    String itemName = framedItem.getHoverName().getString();
                    sb.append(" [展示框内物品: ").append(itemName).append("]");
                    detailValue = resolveItemId(framedItem);
                    detailPrefix = DetailTag.NATIVE_SPECIFIC;
                }
            }

            builder.addTaggedEntry(TaggedContextEntry.builder(environmentSource)
                    .focus(FocusTag.COMMON)
                    .detail(detailPrefix, detailValue)
                    .description(sb.toString())
                    .importance(ImportanceRegistry.getInstance().get("look:entity", 1.0f))
                    .crosshairTarget(true)
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
            String blockId = resolveBlockId(blockState);

            double dist = Math.sqrt(player.blockPosition().distSqr(pos));

            StringBuilder sb = new StringBuilder();
            sb.append("当前准心目标: ").append(blockName);
            sb.append(" (坐标 ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ())
                    .append(")");
            sb.append(" (距离 ").append(String.format("%.1f", dist)).append("格)");

            builder.addTaggedEntry(TaggedContextEntry.builder(environmentSource)
                    .focus(FocusTag.COMMON)
                    .detail(DetailTag.NATIVE_SPECIFIC, blockId)
                    .description(sb.toString())
                    .importance(ImportanceRegistry.getInstance().get("look:block", 1.0f))
                    .crosshairTarget(true)
                    .build());
            return;
        }

        // ── 什么也没看 ──
        builder.extra("looking_at", "当前准心目标: 天空/远方(无目标)");
    }

    private String resolveEntityId(Entity entity) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null ? id.toString() : entity.getType().toString();
    }

    private String resolveItemId(ItemStack stack) {
        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null) {
            return id.toString();
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            var blockId = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
            if (blockId != null) {
                return blockId.toString();
            }
        }
        return stack.getHoverName().getString();
    }

    private String resolveBlockId(BlockState blockState) {
        var id = ForgeRegistries.BLOCKS.getKey(blockState.getBlock());
        return id != null ? id.toString() : blockState.getBlock().getName().getString();
    }
}
