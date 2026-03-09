package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * 基础上下文采集器：采集玩家核心状态。
 * <ul>
 *   <li>生命值 / 最大生命值</li>
 *   <li>饥饿值</li>
 *   <li>坐标 (x, y, z)</li>
 *   <li>所在群系</li>
 *   <li>游戏内时间</li>
 *   <li>天气（是否下雨）</li>
 * </ul>
 * 几乎所有触发器都应包含此采集器。
 */
public class BaseContextCollector implements IContextCollector {

    @Override
    public String getName() {
        return "base";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player != null) {
            builder.health(player.getHealth())
                   .maxHealth(player.getMaxHealth())
                   .foodLevel(player.getFoodData().getFoodLevel())
                   .pos(player.getX(), player.getY(), player.getZ());
        }

        if (level != null) {
            builder.dayTime(level.getDayTime())
                   .raining(level.isRaining());

            if (player != null) {
                Holder<Biome> biomeHolder = level.getBiome(player.blockPosition());
                biomeHolder.unwrapKey().ifPresent(key ->
                        builder.biome(key.location().toString())
                );
            }
        }
    }
}
