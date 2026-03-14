package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * 玩家环境采集器：采集与玩家直接相关的环境细节。
 * <ul>
 * <li>维度（主世界 / 下界 / 末地）</li>
 * <li>脚下光照等级</li>
 * <li>主手持有物品</li>
 * <li>盔甲值</li>
 * <li>准星目标方块</li>
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
        if (player == null || level == null)
            return;

        // ── 标签：维度 ──
        ResourceKey<Level> dimKey = level.dimension();
        String dimId = dimKey.location().toString();
        builder.addTaggedEntry(TaggedContextEntry.builder()
                .focus(FocusTag.LANDSCAPE)
                .detail(DetailTag.DIM, dimId)
                .description("主人目前身处于: " + dimId)
                .importance(0.3f)
                .build());

        // ── 标签：光照等级 ──
        BlockPos playerPos = player.blockPosition();
        int blockLight = level.getBrightness(LightLayer.BLOCK, playerPos);
        int skyLight = level.getBrightness(LightLayer.SKY, playerPos);
        int effectiveLight = Math.max(blockLight, skyLight);
        String lightState = effectiveLight < 7 ? "light_dark" : "light_bright";
        float lightImportance = effectiveLight < 7 ? 0.3f : 0.1f;
        builder.addTaggedEntry(TaggedContextEntry.builder()
                .focus(effectiveLight < 7 ? FocusTag.HAZARD : FocusTag.LANDSCAPE)
                .detail(DetailTag.STATE, lightState)
                .description("主人周围的环境光照阴暗程度感知为: " + (effectiveLight < 7 ? "昏暗且危险 (容易生成怪物)" : "明亮安全"))
                .importance(lightImportance)
                .build());

        // ── 标签：主手物品 ──
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            String itemName = mainHand.getHoverName().getString();
            String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getKey(mainHand.getItem()).toString();
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.TOOL)
                    .detail(DetailTag.ITEM, itemId)
                    .description("主人的手中正紧紧握着: " + itemName)
                    .importance(0.2f)
                    .build());
        }

        // ── 标签：经验等级 ──
        if (player.experienceLevel > 0) {
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.STATE, "xp_level")
                    .description("主人当前积攒的经验等级为: " + player.experienceLevel)
                    .importance(0.1f)
                    .build());
        }
    }
}
