package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
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

        // 输出 ActionBuffer 快照日志供调试（转移前）
        dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger.logActionBufferDump(actionBuffer);

        // 获取快照进行提取
        List<ActionRecord> records = actionBuffer.snapshot();

        // 【关键】阅后即焚：将已经被看到的动作转移至“已读池”中
        // 防止下一次右键重复看到同样的动作幻觉，但为后续的 反思任务 (ReflectionTask) 保留记录
        actionBuffer.drainTo(AgentRuntime.getInstance().getMemory().getConsumedActions());

        // 按 PrimaryFocusTag 进行分组
        java.util.Map<FocusTag, List<ActionRecord>> groupedRecords = actionBuffer.groupRecordsByFocus(records);

        // 对每一个领域类别生成单独的上下文明细条目
        for (java.util.Map.Entry<FocusTag, List<ActionRecord>> entry : groupedRecords.entrySet()) {
            FocusTag focus = entry.getKey();
            List<ActionRecord> group = entry.getValue();

            String groupSummary = actionBuffer.createSummaryForGroup(group);

            // 根据焦点类型决定该组动作的初始剧情重要度和描述文案
            float importance;
            String descPrefix;
            switch (focus) {
                case COMBAT:
                case HAZARD:
                    importance = ImportanceRegistry.getInstance().get("action:combat", 0.8f);
                    descPrefix = "主人刚刚经历了一场战斗，动作包括: ";
                    break;
                case LABOR:
                    importance = ImportanceRegistry.getInstance().get("action:labor", 0.4f);
                    descPrefix = "主人刚刚进行了一系列劳作，包括: ";
                    break;
                case STATUS:
                case CONSUMABLE:
                    importance = ImportanceRegistry.getInstance().get("action:status", 0.6f);
                    descPrefix = "由于状态需求，主人刚刚进行了以下动作: ";
                    break;
                case MOVEMENT:
                    importance = ImportanceRegistry.getInstance().get("action:movement", 0.5f);
                    descPrefix = "主人刚刚经历了一段旅程: ";
                    break;
                case SOCIAL:
                    importance = ImportanceRegistry.getInstance().get("action:social", 0.7f);
                    descPrefix = "主人刚刚与周围的世界进行了一些互动: ";
                    break;
                default:
                    importance = ImportanceRegistry.getInstance().get("action:default", 0.3f);
                    descPrefix = "主人刚刚进行了一些动作: ";
                    break;
            }

            // 收集该组动作涉及的所有次要标签和详情标签
            EnumSet<FocusTag> allFocusTags = EnumSet.of(focus);
            Set<DetailTag> allDetailTags = new LinkedHashSet<>();
            for (ActionRecord r : group) {
                allFocusTags.addAll(r.getType().getAllFocusTags());
                allDetailTags.addAll(r.getDetailTags());
            }

            TaggedContextEntry.Builder entryBuilder = TaggedContextEntry.builder()
                    .focus(allFocusTags.toArray(new FocusTag[0]))
                    .description(descPrefix + groupSummary)
                    .importance(importance);

            for (DetailTag tag : allDetailTags) {
                entryBuilder.detail(tag);
            }

            builder.addTaggedEntry(entryBuilder.build());
        }
    }
}
