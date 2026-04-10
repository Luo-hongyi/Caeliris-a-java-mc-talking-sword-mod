package dreamdust.talking_weapon.basic_talking_weapon.agent.testing;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSource;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextSourceType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionMessageKind;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;

import java.util.List;
import java.util.Map;

/**
 * 本地自检：验证 tagged context source 与 interaction message 的核心语义。
 */
public final class InteractionSourceSelfTest {

    private InteractionSourceSelfTest() {
    }

    public static void main(String[] args) {
        verifyTaggedContextSources();
        verifyInteractionMessages();
        verifyInteractionTurn();
        System.out.println("[InteractionSourceSelfTest] PASS");
    }

    private static void verifyTaggedContextSources() {
        TaggedContextSource player = TaggedContextSource.player("player-1", "Jack");
        TaggedContextSource agent = TaggedContextSource.agentSelf();
        TaggedContextSource environment = TaggedContextSource.environment();

        expect(player.getType() == TaggedContextSourceType.PLAYER, "player source type");
        expect("player-1".equals(player.getSourceId()), "player source id");
        expect("Jack".equals(player.getDisplayName()), "player display name");

        expect(agent.getType() == TaggedContextSourceType.AGENT, "agent source type");
        expect("self".equals(agent.getSourceId()), "agent source id");

        expect(environment.getType() == TaggedContextSourceType.ENVIRONMENT, "environment source type");
        expect("environment".equals(environment.getSourceId()), "environment source id");
    }

    private static void verifyInteractionMessages() {
        InteractionMessage playerMessage = InteractionMessage.playerMessage(
                TaggedContextSource.player("player-1", "Jack"),
                "你好");
        InteractionMessage speech = InteractionMessage.agentSpeech("别冲太深。");
        InteractionMessage monologue = InteractionMessage.agentInnerMonologue("前面不太对。");
        InteractionMessage cue = InteractionMessage.agentReactionCue("对眼前危险做出反应。");

        expect(playerMessage.getSource().getType() == TaggedContextSourceType.PLAYER, "player message source");
        expect(playerMessage.getKind() == InteractionMessageKind.TEXT, "player message kind");

        expect(speech.getSource().getType() == TaggedContextSourceType.AGENT, "speech source");
        expect(speech.getKind() == InteractionMessageKind.TEXT, "speech kind");

        expect(monologue.getSource().getType() == TaggedContextSourceType.AGENT, "monologue source");
        expect(monologue.getKind() == InteractionMessageKind.INNER_MONOLOGUE, "monologue kind");

        expect(cue.getSource().getType() == TaggedContextSourceType.AGENT, "reaction cue source");
        expect(cue.getKind() == InteractionMessageKind.REACTION_CUE, "reaction cue kind");
    }

    private static void verifyInteractionTurn() {
        TaggedContextEntry playerContext = TaggedContextEntry.builder(TaggedContextSource.player("player-1", "Jack"))
                .focus(FocusTag.EXPLORE)
                .detail(DetailTag.customGeneric("careful_step"))
                .description("玩家站在洞口边缘。")
                .timestamp(123L)
                .build();
        TaggedContextEntry environmentContext = TaggedContextEntry.builder(TaggedContextSource.environment())
                .focus(FocusTag.HAZARD)
                .detail(DetailTag.nativeSpecific("minecraft:lava"))
                .description("前方有一片岩浆池。")
                .timestamp(124L)
                .build();

        InteractionTurn turn = new InteractionTurn(
                "danger_event",
                null,
                List.of(
                        InteractionMessage.agentReactionCue("对眼前危险做出反应。"),
                        InteractionMessage.agentSpeech("别再往前了。"),
                        InteractionMessage.agentInnerMonologue("她差点直接踩进去。")),
                List.of(playerContext, environmentContext),
                Map.of("biome", "minecraft:dripstone_caves"));

        expect(turn.getMessages().size() == 3, "turn message count");
        expect(turn.getMessages().get(0).getKind() == InteractionMessageKind.REACTION_CUE, "turn first message kind");
        expect(turn.getVisibleContexts().size() == 2, "turn visible context count");
        expect(turn.getVisibleContexts().get(0).getSource().getType() == TaggedContextSourceType.PLAYER,
                "turn player visible context source");
        expect(turn.getVisibleContexts().get(1).getSource().getType() == TaggedContextSourceType.ENVIRONMENT,
                "turn environment visible context source");
        expect("minecraft:dripstone_caves".equals(turn.getContextExtras().get("biome")), "turn extras");
    }

    private static void expect(boolean condition, String label) {
        if (!condition) {
            throw new IllegalStateException("Self test failed: " + label);
        }
    }
}
