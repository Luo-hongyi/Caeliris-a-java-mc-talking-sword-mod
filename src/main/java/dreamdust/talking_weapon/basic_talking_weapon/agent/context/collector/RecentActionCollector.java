package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.RecentActionBuffer;
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
 * 从 {@link RecentActionBuffer} 读取玩家最近的高频动作流（挖掘、杀敌、受击等），
 * 并提炼汇总注入到上下文中，让剑能直观感知刚发生的战斗或劳作。
 */
public class RecentActionCollector implements IContextCollector {

    /**
     * 当前 prompt 侧只消费已经稳定接入语义的旧动作。
     * <p>
     * 新增的 intent/LTM 专用动作先留在 ActionArchive 中，避免在提示词里与旧语义混用。
     */
    private static final EnumSet<ActionType> PROMPT_VISIBLE_ACTIONS = EnumSet.of(
            ActionType.ATTACK,
            ActionType.KILL,
            ActionType.HURT_BY,
            ActionType.DEATH,
            ActionType.MINE_BLOCK,
            ActionType.PLACE_BLOCK,
            ActionType.EAT,
            ActionType.USE_ITEM,
            ActionType.TRAVEL,
            ActionType.BIOME_CHANGE,
            ActionType.DIM_CHANGE,
            ActionType.TRADE
    );

    @Override
    public String getName() {
        return "recent_actions";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        RecentActionBuffer recentActionBuffer = AgentRuntime.getInstance().getMemory().getRecentActionBuffer();
        if (recentActionBuffer.isEmpty()) {
            return;
        }

        // 输出近期动作缓冲快照日志供调试（消费前）
        dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger.logRecentActionBufferDump(recentActionBuffer);

        // 当前 prompt 只消费稳定动作；原始动作全量副本已经在 ActionArchive 中保留。
        List<ActionRecord> records = recentActionBuffer.consumeMatching(
                record -> PROMPT_VISIBLE_ACTIONS.contains(record.getType()));
        if (records.isEmpty()) {
            return;
        }

        // 按 PrimaryFocusTag 进行分组
        java.util.Map<FocusTag, List<ActionRecord>> groupedRecords = recentActionBuffer.groupRecordsByFocus(records);

        // 对每一个领域类别生成单独的上下文明细条目
        for (java.util.Map.Entry<FocusTag, List<ActionRecord>> entry : groupedRecords.entrySet()) {
            FocusTag focus = entry.getKey();
            List<ActionRecord> group = entry.getValue();

            String groupSummary = recentActionBuffer.createSummaryForGroup(group);

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
