package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PlayerIdentityRegistry;
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
        TaggedContextSource playerSource = PlayerIdentityRegistry.getInstance().resolveSource(player);
        TaggedContextSource environmentSource = TaggedContextSource.environment();

        // ── 标签：维度 ──
        ResourceKey<Level> dimKey = level.dimension();
        String dimId = dimKey.location().toString();
        builder.extra("dimension", dimId);
        
        builder.addTaggedEntry(TaggedContextEntry.builder(environmentSource)
                .focus(FocusTag.LANDSCAPE)
                .detail(DetailTag.NATIVE_SPECIFIC, dimId)
                .description("当前位置维度: " + dimId)
                .importance(ImportanceRegistry.getInstance().get("env:dimension", 0.3f))
                .associativeOnly(true)
                .build());

        // ── 标签：光照等级 ──
        BlockPos playerPos = player.blockPosition();
        int blockLight = level.getBrightness(LightLayer.BLOCK, playerPos);
        int skyLight = level.getBrightness(LightLayer.SKY, playerPos);
        int effectiveLight = Math.max(blockLight, skyLight);
        String lightState = effectiveLight < 7 ? "light_dark" : "light_bright";
        float lightImportance = effectiveLight < 7 ? ImportanceRegistry.getInstance().get("env:light_level", 0.3f) : 0.1f;
        builder.addTaggedEntry(TaggedContextEntry.builder(environmentSource)
                .focus(effectiveLight < 7 ? FocusTag.HAZARD : FocusTag.LANDSCAPE)
                .detail(DetailTag.CUSTOM_SPECIFIC, lightState)
                .description("当前位置环境光照: " + (effectiveLight < 7 ? "昏暗且危险 (容易生成怪物)" : "明亮安全"))
                .importance(lightImportance)
                .build());

        // ── 标签：主手物品 ──
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            String itemName = mainHand.getHoverName().getString();
            String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getKey(mainHand.getItem()).toString();
            builder.addTaggedEntry(TaggedContextEntry.builder(playerSource)
                    .focus(FocusTag.TOOL)
                    .detail(DetailTag.NATIVE_SPECIFIC, itemId)
                    .description("玩家主手持有: " + itemName)
                    .importance(ImportanceRegistry.getInstance().get("env:held_item", 0.2f))
                    .build());
        }

        // ── 标签：经验等级 ──
        if (player.experienceLevel > 0) {
            builder.addTaggedEntry(TaggedContextEntry.builder(playerSource)
                    .focus(FocusTag.STATUS)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "xp_level")
                    .description("玩家当前经验等级: " + player.experienceLevel)
                    .importance(ImportanceRegistry.getInstance().get("env:xp", 0.1f))
                    .build());
        }
    }
}
