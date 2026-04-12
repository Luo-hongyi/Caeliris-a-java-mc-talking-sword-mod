package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSourceType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessageKind;
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
        StringBuilder referenceMap = new StringBuilder();

        draft.append("[Reflection Material]\n");
        draft.append("scope: ").append(material.scope()).append("\n");
        draft.append("region id: ").append(material.regionId()).append("\n");
        draft.append("segment id range: ")
                .append(material.startSegmentId())
                .append(" -> ")
                .append(material.endSegmentId())
                .append("\n");
        draft.append("time range: ")
                .append(material.startTimestamp())
                .append(" -> ")
                .append(material.endTimestamp())
                .append("\n");
        draft.append("stm slice: ")
                .append(material.stmSliceStartTimestamp())
                .append(" -> ")
                .append(material.stmSliceExclusiveTimestamp())
                .append("\n");
        draft.append("episode close state: ")
                .append(describeEpisodeCloseState(material.episodeCloseStateSnapshot()))
                .append("\n");
        draft.append("segment count: ").append(material.segments().size()).append("\n\n");

        for (ReflectionSegmentMaterial segment : material.segments()) {
            if (segment == null) {
                continue;
            }
            if (!referenceMap.isEmpty()) {
                referenceMap.append('\n');
            }
            referenceMap.append(segment.localSegmentIndex())
                    .append(" -> segmentId=")
                    .append(segment.segmentId());

            draft.append("[Segment ").append(segment.localSegmentIndex()).append("]\n");
            draft.append("segment id: ").append(segment.segmentId()).append("\n");
            draft.append("range: ").append(segment.startTimestamp()).append(" -> ").append(segment.endTimestamp()).append("\n");
            draft.append("segment close state: ")
                    .append(describeSegmentCloseState(segment.segmentCloseStateSnapshot()))
                    .append("\n");
            appendSnapshot(draft, "segment snapshot", segment.segmentSnapshot());
            draft.append("timeline:\n");
            for (ReflectionSegmentEvent event : segment.timelineEvents()) {
                if (event == null) {
                    continue;
                }
                switch (event.type()) {
                    case SEGMENT_SNAPSHOT -> draft.append("- segment snapshot baseline\n");
                    case LOCAL_STM_CONTEXT -> draft.append("- local stm context: ")
                            .append(describeContextEntry(event.contextEntry()))
                            .append("\n");
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

        return new ReflectionPromptDraft(draft.toString().trim(), referenceMap.toString().trim());
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
            descriptions.add(describeContextSummary(entry));
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

    private String describeSegmentCloseState(
            dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentStateSnapshot snapshot) {
        if (snapshot == null) {
            return "(none)";
        }
        List<String> fields = new ArrayList<>();
        if (snapshot.primaryEmotion() != null && !snapshot.primaryEmotion().isBlank()) {
            fields.add("emotion=" + snapshot.primaryEmotion());
        }
        if (snapshot.emotionIntensity() != null) {
            fields.add("intensity=" + snapshot.emotionIntensity());
        }
        if (snapshot.favorability() != null) {
            fields.add("favorability=" + snapshot.favorability());
        }
        return fields.isEmpty() ? "(none)" : String.join(", ", fields);
    }

    private String describeEpisodeCloseState(
            dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionEpisodeStateSnapshot snapshot) {
        if (snapshot == null) {
            return "(none)";
        }
        List<String> fields = new ArrayList<>();
        if (snapshot.playerProfile() != null && !snapshot.playerProfile().isBlank()) {
            fields.add("player_profile=" + cleanLine(snapshot.playerProfile()));
        }
        if (snapshot.recap() != null && !snapshot.recap().isBlank()) {
            fields.add("recap=" + cleanLine(snapshot.recap()));
        }
        return fields.isEmpty() ? "(none)" : String.join(" | ", fields);
    }

    private String describeContextEntry(TaggedContextEntry entry) {
        if (entry == null) {
            return "(unknown)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(describeContextSource(entry)).append("] ");
        String description = cleanLine(entry.getDescription());
        sb.append(description.isBlank() ? "(empty)" : description);
        if (!entry.getDetailTags().isEmpty()) {
            sb.append(" [tags=").append(entry.getDetailTags()).append(']');
        }
        return sb.toString();
    }

    private String describeContextSummary(TaggedContextEntry entry) {
        if (entry == null) {
            return "(unknown)";
        }
        String description = cleanLine(entry.getDescription());
        return "[" + describeContextSource(entry) + "] " + (description.isBlank() ? "(empty)" : description);
    }

    private String describeContextSource(TaggedContextEntry entry) {
        if (entry == null || entry.getSource() == null) {
            return "unknown";
        }
        TaggedContextSource source = entry.getSource();
        return switch (source.getType()) {
            case PLAYER -> "player:" + cleanLine(firstNonBlank(source.getDisplayName(), source.getSourceId()));
            case AGENT -> "agent:" + cleanLine(firstNonBlank(source.getDisplayName(), source.getSourceId(), "self"));
            case ENVIRONMENT -> "environment";
        };
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String describeInteraction(ReflectionInteraction interaction) {
        if (interaction == null) {
            return "(unknown)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("trigger=").append(interaction.triggerSource().isBlank() ? "unknown" : interaction.triggerSource());
        if (!interaction.messages().isEmpty()) {
            List<String> outputs = new ArrayList<>();
            for (InteractionMessage output : interaction.messages()) {
                if (output == null
                        || output.getSource() == null
                        || output.getKind() == null
                        || output.getText() == null
                        || output.getText().isBlank()) {
                    continue;
                }
                outputs.add(describeInteractionMessage(output) + ":" + cleanLine(output.getText()));
            }
            if (!outputs.isEmpty()) {
                sb.append(", messages=").append(String.join(" ; ", outputs));
            }
        }
        if (interaction.boundSnapshot() != null && !interaction.boundSnapshot().visibleContexts().isEmpty()) {
            sb.append(", bound_visible=").append(joinContextDescriptions(interaction.boundSnapshot().visibleContexts()));
        }
        if (interaction.boundSnapshot() != null && !interaction.boundSnapshot().contextExtras().isEmpty()) {
            sb.append(", bound_extras=").append(joinExtras(interaction.boundSnapshot().contextExtras()));
        }
        return sb.toString();
    }

    private String cleanLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').trim();
    }

    private String describeInteractionMessage(InteractionMessage message) {
        if (message == null || message.getSource() == null || message.getKind() == null) {
            return "unknown";
        }
        if (message.getKind() == InteractionMessageKind.REACTION_CUE) {
            return "agent_reaction_cue";
        }
        TaggedContextSourceType sourceType = message.getSource().getType();
        return switch (sourceType) {
            case PLAYER -> "player_msg";
            case AGENT -> message.getKind() == InteractionMessageKind.INNER_MONOLOGUE
                    ? "agent_inner_monologue"
                    : "agent_speech";
            case ENVIRONMENT -> "environment_msg";
        };
    }
}
