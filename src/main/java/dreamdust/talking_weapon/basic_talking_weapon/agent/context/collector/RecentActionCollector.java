package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionBuffer;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 近期动作采集器。
 * <p>
 * 从 {@link ActionBuffer} 读取玩家最近的高频动作流（挖掘、杀敌、受击等），
 * 并提炼汇总注入到上下文中，让剑能直观感知刚发生的战斗或劳作。
 */
public class RecentActionCollector implements IContextCollector {

    @Override
    public String getName() {
        return "recent_actions";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        ActionBuffer actionBuffer = AgentRuntime.getInstance().getMemory().getActionBuffer();
        if (actionBuffer.isEmpty()) {
            return;
        }

        // 提取汇总摘要文本，例如 "ATTACK zombie ×5, MINE_BLOCK diamond_ore"
        String summary = actionBuffer.toContextSummary();
        builder.extra("recent_action_summary", summary);

        // 收集动作流中所有的 FocusTag 和 DetailTag，加到上下文中
        List<ActionRecord> records = actionBuffer.snapshot();
        EnumSet<FocusTag> focusTags = EnumSet.noneOf(FocusTag.class);
        Set<DetailTag> detailTags = new LinkedHashSet<>();

        for (ActionRecord record : records) {
            focusTags.addAll(record.getType().getAllFocusTags());
            detailTags.addAll(record.getDetailTags());
        }

        TaggedContextEntry.Builder entryBuilder = TaggedContextEntry.builder()
                .focus(focusTags.toArray(new FocusTag[0]))
                .description("近期动作汇总: " + summary)
                .importance(0.8f);

        for (DetailTag tag : detailTags) {
            entryBuilder.detail(tag);
        }

        builder.addTaggedEntry(entryBuilder.build());
    }
}
