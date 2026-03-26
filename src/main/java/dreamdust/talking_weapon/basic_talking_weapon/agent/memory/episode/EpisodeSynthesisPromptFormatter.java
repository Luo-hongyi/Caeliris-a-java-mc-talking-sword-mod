package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.ContextSupplementaryFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Episode synthesis 用的时间流提示词骨架。
 * <p>
 * 当前明确采用两种段内叙事形态：
 * <ul>
 * <li>无 interaction：环境 context + 玩家动作列表</li>
 * <li>有 interaction：玩家动作 / interaction+绑定 visible context / 玩家动作 的时间流</li>
 * </ul>
 */
public final class EpisodeSynthesisPromptFormatter {

    private EpisodeSynthesisPromptFormatter() {
    }

    public static String buildPromptDraft(EpisodeSynthesisMaterial material) {
        if (material == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Episode Synthesis Draft]\n");
        sb.append("region 起始 segment index: ").append(material.signal().regionStartSegmentIndex()).append("\n");
        sb.append("region 结束 segment index: ").append(material.signal().regionEndSegmentIndex()).append("\n");
        sb.append("region 内 segment 数量: ").append(material.segments().size()).append("\n\n");

        for (int i = 0; i < material.segments().size(); i++) {
            FixedActionSegment segment = material.segments().get(i);
            if (segment == null) {
                continue;
            }
            int segmentIndex = material.signal().regionStartSegmentIndex() + i;
            appendSegment(sb, segmentIndex, segment);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static void appendSegment(StringBuilder sb, int segmentIndex, FixedActionSegment segment) {
        FixedActionSegmentContext context = segment.getContext();
        List<InteractionTurn> turns = context == null ? List.of() : context.getInteractionTurns();

        sb.append("[Segment ").append(segmentIndex).append("]\n");
        sb.append("范围: ").append(segment.getStartTimestamp()).append(" -> ").append(segment.getEndTimestamp()).append("\n");
        appendSupplementaryInfo(sb, context);

        if (turns.isEmpty()) {
            appendQuietSegment(sb, segment, context);
            return;
        }

        sb.append("时间流:\n");
        for (TimelineEvent event : buildTimeline(segment, context)) {
            if (event.actionEntry() != null) {
                sb.append("- 玩家动作: ").append(describeActionEntry(event.actionEntry())).append("\n");
                continue;
            }
            sb.append("- 交互: ")
                    .append(describeInteraction(event.turn()))
                    .append("\n");
            if (!event.visibleContexts().isEmpty()) {
                sb.append("  绑定 context: ").append(joinContextDescriptions(event.visibleContexts())).append("\n");
            }
        }
    }

    private static void appendQuietSegment(
            StringBuilder sb,
            FixedActionSegment segment,
            FixedActionSegmentContext context) {
        List<TaggedContextEntry> segmentContexts = context == null
                ? List.of()
                : context.getEffectiveVisibleContextEntries();
        if (!segmentContexts.isEmpty()) {
            sb.append("周围 context: ").append(joinContextDescriptions(segmentContexts)).append("\n");
        }

        if (segment.getEntries().isEmpty()) {
            sb.append("玩家动作: (无)\n");
            return;
        }

        sb.append("玩家动作:\n");
        for (FixedActionSegmentEntry entry : segment.getEntries()) {
            sb.append("- ").append(describeActionEntry(entry)).append("\n");
        }
    }

    private static void appendSupplementaryInfo(
            StringBuilder sb,
            FixedActionSegmentContext context) {
        if (context == null) {
            return;
        }
        if (ContextSupplementaryFormatter.appendSection(
                sb,
                "环境补充:",
                "- ",
                context.getEffectiveContextExtras())) {
            sb.append("\n");
        }
    }

    private static List<TimelineEvent> buildTimeline(
            FixedActionSegment segment,
            FixedActionSegmentContext context) {
        ArrayList<TimelineEvent> events = new ArrayList<>();
        for (FixedActionSegmentEntry entry : segment.getEntries()) {
            if (entry == null) {
                continue;
            }
            events.add(TimelineEvent.forAction(entry));
        }
        if (context != null) {
            for (InteractionTurn turn : context.getInteractionTurns()) {
                if (turn == null) {
                    continue;
                }
                events.add(TimelineEvent.forInteraction(
                        turn,
                        resolveInteractionVisibleContexts(turn, context)));
            }
        }
        events.sort(TimelineEvent.ORDER);
        return List.copyOf(events);
    }

    private static List<TaggedContextEntry> resolveInteractionVisibleContexts(
            InteractionTurn turn,
            FixedActionSegmentContext context) {
        if (turn != null && !turn.getVisibleContexts().isEmpty()) {
            return turn.getVisibleContexts();
        }
        if (context == null) {
            return List.of();
        }
        return context.getEffectiveVisibleContextEntries();
    }

    private static String describeActionEntry(FixedActionSegmentEntry entry) {
        if (entry == null || entry.getType() == null) {
            return "(unknown)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(entry.getType().name());
        if (entry.getTarget() != null && !entry.getTarget().isBlank()) {
            sb.append(' ').append(entry.getTarget());
        }
        if (entry.getCount() > 1) {
            sb.append(" ×").append(entry.getCount());
        }
        return sb.toString();
    }

    private static String describeInteraction(InteractionTurn turn) {
        if (turn == null) {
            return "(unknown)";
        }

        StringBuilder sb = new StringBuilder();
        String userPrompt = turn.getUserPrompt();
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("玩家发出指令/事件「").append(cleanLine(userPrompt)).append("」");
        } else {
            sb.append("触发源 ").append(turn.getTriggerSource() == null ? "unknown" : turn.getTriggerSource());
        }

        List<String> outputs = new ArrayList<>();
        for (AgentOutputRecord output : turn.getAgentActions()) {
            if (output == null || output.getText() == null || output.getText().isBlank()) {
                continue;
            }
            if (output.getType() == AgentOutputRecord.Type.SPEECH) {
                outputs.add("剑说「" + cleanLine(output.getText()) + "」");
            } else if (output.getType() == AgentOutputRecord.Type.INNER_MONOLOGUE) {
                outputs.add("剑心声「" + cleanLine(output.getText()) + "」");
            } else {
                outputs.add(output.getType() + "「" + cleanLine(output.getText()) + "」");
            }
        }

        if (outputs.isEmpty()) {
            sb.append("，剑未留下明确输出");
        } else {
            sb.append("，随后").append(String.join("；", outputs));
        }
        return sb.toString();
    }

    private static String joinContextDescriptions(List<TaggedContextEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(无)";
        }
        ArrayList<String> descriptions = new ArrayList<>();
        for (TaggedContextEntry entry : entries) {
            if (entry == null || entry.getDescription() == null || entry.getDescription().isBlank()) {
                continue;
            }
            descriptions.add(cleanLine(entry.getDescription()));
        }
        if (descriptions.isEmpty()) {
            return "(无)";
        }
        return String.join(" | ", descriptions);
    }

    private static String cleanLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private record TimelineEvent(
            long timestamp,
            int priority,
            FixedActionSegmentEntry actionEntry,
            InteractionTurn turn,
            List<TaggedContextEntry> visibleContexts) {

        private static final Comparator<TimelineEvent> ORDER =
                Comparator.comparingLong(TimelineEvent::timestamp)
                        .thenComparingInt(TimelineEvent::priority);

        private TimelineEvent {
            visibleContexts = visibleContexts == null || visibleContexts.isEmpty()
                    ? List.of()
                    : List.copyOf(visibleContexts);
        }

        private static TimelineEvent forAction(FixedActionSegmentEntry entry) {
            return new TimelineEvent(
                    entry.getFirstTimestamp(),
                    0,
                    entry,
                    null,
                    List.of());
        }

        private static TimelineEvent forInteraction(
                InteractionTurn turn,
                List<TaggedContextEntry> visibleContexts) {
            return new TimelineEvent(
                    turn.getTimestamp(),
                    1,
                    null,
                    turn,
                    visibleContexts);
        }
    }
}
