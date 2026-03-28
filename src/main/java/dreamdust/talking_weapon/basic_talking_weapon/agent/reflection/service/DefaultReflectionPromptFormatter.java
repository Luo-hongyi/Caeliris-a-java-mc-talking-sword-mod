package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionInteraction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionPlayerAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentEvent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionPromptDraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 第一版 reflection prompt formatter。
 * <p>
 * 当前只把统一 material 投影成通用文本草稿，
 * task 自己再叠任务 instruction。
 */
public final class DefaultReflectionPromptFormatter implements ReflectionPromptFormatter {

    @Override
    public ReflectionPromptDraft format(ReflectionMaterial material) {
        if (material == null) {
            return new ReflectionPromptDraft("", "");
        }

        StringBuilder draft = new StringBuilder();
        StringBuilder indexMap = new StringBuilder();

        draft.append("[Reflection Material]\n");
        draft.append("scope: ").append(material.scope()).append("\n");
        draft.append("timeline segment range: ")
                .append(material.startTimelineSegmentIndex())
                .append(" -> ")
                .append(material.endTimelineSegmentIndex())
                .append("\n");
        if (material.startSegmentId() > 0L || material.endSegmentId() > 0L) {
            draft.append("segment id range: ")
                    .append(material.startSegmentId())
                    .append(" -> ")
                    .append(material.endSegmentId())
                    .append("\n");
        }
        draft.append("segment count: ").append(material.segments().size()).append("\n\n");

        for (ReflectionSegmentMaterial segment : material.segments()) {
            if (segment == null) {
                continue;
            }
            if (!indexMap.isEmpty()) {
                indexMap.append('\n');
            }
            indexMap.append(segment.localSegmentIndex())
                    .append(" -> timeline#")
                    .append(segment.timelineSegmentIndex());
            if (segment.segmentId() > 0L) {
                indexMap.append(" (segmentId=").append(segment.segmentId()).append(")");
            }

            draft.append("[Segment ").append(segment.localSegmentIndex()).append("]\n");
            draft.append("timeline index: ").append(segment.timelineSegmentIndex()).append("\n");
            if (segment.segmentId() > 0L) {
                draft.append("segment id: ").append(segment.segmentId()).append("\n");
            }
            draft.append("range: ").append(segment.startTimestamp()).append(" -> ").append(segment.endTimestamp()).append("\n");
            appendSnapshot(draft, "start snapshot", segment.startSnapshot());
            draft.append("timeline:\n");
            for (ReflectionSegmentEvent event : segment.timelineEvents()) {
                if (event == null) {
                    continue;
                }
                switch (event.type()) {
                    case SEGMENT_START_SNAPSHOT -> draft.append("- snapshot baseline\n");
                    case PLAYER_ACTION -> draft.append("- player action: ")
                            .append(describePlayerAction(event.playerAction()))
                            .append("\n");
                    case AGENT_INTERACTION -> draft.append("- agent interaction: ")
                            .append(describeInteraction(event.interaction()))
                            .append("\n");
                }
            }
            draft.append("\n");
        }

        return new ReflectionPromptDraft(draft.toString().trim(), indexMap.toString().trim());
    }

    private void appendSnapshot(StringBuilder sb, String label, ReflectionContextSnapshot snapshot) {
        if (snapshot == null) {
            sb.append(label).append(": (empty)\n");
            return;
        }
        sb.append(label).append(" visible: ").append(joinContextDescriptions(snapshot.visibleContexts())).append("\n");
        sb.append(label).append(" extras: ").append(joinExtras(snapshot.contextExtras())).append("\n");
    }

    private String joinContextDescriptions(List<TaggedContextEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(none)";
        }
        List<String> descriptions = new ArrayList<>();
        for (TaggedContextEntry entry : entries) {
            if (entry == null || entry.getDescription() == null || entry.getDescription().isBlank()) {
                continue;
            }
            descriptions.add(cleanLine(entry.getDescription()));
        }
        return descriptions.isEmpty() ? "(none)" : String.join(" | ", descriptions);
    }

    private String joinExtras(Map<String, String> extras) {
        if (extras == null || extras.isEmpty()) {
            return "(none)";
        }
        StringJoiner joiner = new StringJoiner(" | ");
        extras.forEach((key, value) -> joiner.add(key + "=" + cleanLine(value)));
        String value = joiner.toString();
        return value.isBlank() ? "(none)" : value;
    }

    private String describePlayerAction(ReflectionPlayerAction action) {
        if (action == null || action.actionType() == null) {
            return "(unknown)";
        }
        StringBuilder sb = new StringBuilder(action.actionType().name());
        if (action.target() != null && !action.target().isBlank()) {
            sb.append(' ').append(action.target());
        }
        if (action.count() > 1) {
            sb.append(" x").append(action.count());
        }
        if (!action.detailTags().isEmpty()) {
            sb.append(" [tags=").append(action.detailTags()).append(']');
        }
        return sb.toString();
    }

    private String describeInteraction(ReflectionInteraction interaction) {
        if (interaction == null) {
            return "(unknown)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("trigger=").append(interaction.triggerSource().isBlank() ? "unknown" : interaction.triggerSource());
        if (!interaction.userPrompt().isBlank()) {
            sb.append(", user=\"").append(cleanLine(interaction.userPrompt())).append("\"");
        }
        if (!interaction.outputs().isEmpty()) {
            List<String> outputs = new ArrayList<>();
            for (AgentOutputRecord output : interaction.outputs()) {
                if (output == null || output.getText() == null || output.getText().isBlank()) {
                    continue;
                }
                outputs.add(output.getType() + ":" + cleanLine(output.getText()));
            }
            if (!outputs.isEmpty()) {
                sb.append(", outputs=").append(String.join(" ; ", outputs));
            }
        }
        if (interaction.boundSnapshot() != null && !interaction.boundSnapshot().visibleContexts().isEmpty()) {
            sb.append(", bound_visible=").append(joinContextDescriptions(interaction.boundSnapshot().visibleContexts()));
        }
        return sb.toString();
    }

    private String cleanLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').trim();
    }
}
