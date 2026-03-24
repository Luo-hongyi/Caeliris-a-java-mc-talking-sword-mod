package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContextCollectionRunner;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.CollectedContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.VisibleContextSelector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector.StandardInteractionCollectorProfile;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 为 fixed segment 起始点采集一份轻量级默认环境快照。
 * <p>
 * 这份快照只在 segment 创建时采一次，用作“该段内完全没有 STM context 时”的兜底材料。
 * <p>
 * 注意：
 * 默认快照复用标准交互的 collector 执行链，
 * 但明确排除 screenshot 与 recent actions 两类 prompt 专属材料。
 */
public final class FixedSegmentDefaultContextCollector {

    private FixedSegmentDefaultContextCollector() {
    }

    public static FixedActionSegmentContext captureCurrentDefaultContext() {
        return FixedActionSegmentContext.withDefaultSnapshot(captureCurrentDefaultSnapshot());
    }

    public static CollectedContextSnapshot captureCurrentDefaultSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return CollectedContextSnapshot.empty();
        }

        AgentContext snapshot = AgentContextCollectionRunner.collect(
                "fixed_segment_default_context",
                StandardInteractionCollectorProfile.build(false, false),
                player,
                level);

        List<TaggedContextEntry> defaultVisibleEntries = VisibleContextSelector.select(
                CollectedContextSnapshot.memoryEligibleEntries(snapshot.getTaggedEntries()),
                snapshot.getTriggerSource(),
                resolveCurrentConcentration()).visibleContexts();
        return CollectedContextSnapshot.fromAgentContext(snapshot, defaultVisibleEntries);
    }

    private static float resolveCurrentConcentration() {
        try {
            return AgentRuntime.getInstance().getState().getConcentration();
        } catch (Throwable ignored) {
            return 0.5f;
        }
    }
}
