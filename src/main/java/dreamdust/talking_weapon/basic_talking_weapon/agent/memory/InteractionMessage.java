package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;

import java.util.Objects;

/**
 * 单轮 interaction 中的最小消息单元。
 */
public final class InteractionMessage {

    private final TaggedContextSource source;
    private final InteractionMessageKind kind;
    private final String text;

    public InteractionMessage(TaggedContextSource source, InteractionMessageKind kind, String text) {
        this.source = Objects.requireNonNull(source, "source");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = text == null ? "" : text;
    }

    public static InteractionMessage playerMessage(TaggedContextSource source, String text) {
        return new InteractionMessage(source, InteractionMessageKind.TEXT, text);
    }

    public static InteractionMessage agentSpeech(String text) {
        return new InteractionMessage(TaggedContextSource.agentSelf(), InteractionMessageKind.TEXT, text);
    }

    public static InteractionMessage agentInnerMonologue(String text) {
        return new InteractionMessage(TaggedContextSource.agentSelf(), InteractionMessageKind.INNER_MONOLOGUE, text);
    }

    public static InteractionMessage agentReactionCue(String text) {
        return new InteractionMessage(TaggedContextSource.agentSelf(), InteractionMessageKind.REACTION_CUE, text);
    }

    public TaggedContextSource getSource() {
        return source;
    }

    public InteractionMessageKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        String preview = text.length() > 40 ? text.substring(0, 40) + "..." : text;
        return "InteractionMessage[" + source.getType() + "/" + kind + ": \"" + preview + "\"]";
    }
}
