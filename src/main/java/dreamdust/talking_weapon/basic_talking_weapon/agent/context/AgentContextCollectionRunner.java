package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.IContextCollector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 统一的上下文采集执行器。
 * <p>
 * 将“按 collector 顺序采集 + 注入运行时 desire”这段通用流程提取出来，
 * 供真实触发器与内部固定快照复用。
 */
public final class AgentContextCollectionRunner {

    private AgentContextCollectionRunner() {
    }

    public static AgentContext.Builder collectIntoBuilder(
            String triggerSource,
            List<IContextCollector> collectors,
            Player player,
            Level level) {
        AgentContext.Builder builder = AgentContext.builder()
                .timestamp(System.currentTimeMillis())
                .triggerSource(triggerSource == null || triggerSource.isBlank() ? "unknown" : triggerSource);

        if (collectors != null) {
            for (IContextCollector collector : collectors) {
                if (collector == null) {
                    continue;
                }
                try {
                    collector.collect(builder, player, level);
                } catch (Throwable t) {
                    System.err.println("[TalkingWeapon] Collector '" + collector.getName() + "' failed: " + t);
                    t.printStackTrace();
                }
            }
        }

        injectRuntimeDesire(builder);
        return builder;
    }

    public static AgentContext collect(
            String triggerSource,
            List<IContextCollector> collectors,
            Player player,
            Level level) {
        return collectIntoBuilder(triggerSource, collectors, player, level).build();
    }

    private static void injectRuntimeDesire(AgentContext.Builder builder) {
        AgentState state;
        try {
            state = AgentRuntime.getInstance().getState();
        } catch (Throwable ignored) {
            state = null;
        }

        if (builder == null || state == null || state.getCurrentDesireTag() == null) {
            return;
        }

        String desc = state.getDesireDescription();
        if (desc == null || desc.isBlank()) {
            desc = "内心的渴望...";
        }
        builder.addTaggedEntry(TaggedContextEntry
                .builder(TaggedContextSource.agentSelf())
                .focus(state.getCurrentDesireTag())
                .detail(DetailTag.CUSTOM_GENERIC, "desire")
                .description("当前渴望: " + desc)
                .importance(0.8f)
                .build());
    }
}
