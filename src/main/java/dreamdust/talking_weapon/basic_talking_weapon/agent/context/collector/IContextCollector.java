package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 上下文采集器接口：每个实现负责采集一类游戏信息。
 * <p>
 * 采集器在<b>主线程</b>上被 {@code AgentSensor} 调用，
 * 将数据写入 {@link AgentContext.Builder}。
 * <p>
 * 触发器通过 {@code getCollectors()} 声明自己需要哪些采集器，
 * AgentSensor 按顺序执行它们。
 */
public interface IContextCollector {

    /** 采集器名称，用于日志和调试 */
    String getName();

    /**
     * 在主线程上采集数据并写入 builder。
     *
     * @param builder 当前正在构建的 AgentContext
     * @param player  当前玩家（可能为 null）
     * @param level   当前世界（可能为 null）
     */
    void collect(AgentContext.Builder builder, Player player, Level level);
}
