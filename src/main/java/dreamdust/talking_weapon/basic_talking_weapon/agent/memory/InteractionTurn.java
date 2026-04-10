package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.CollectedContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整的交互回合。
 * <p>
 * 封装了一次“诱发 -> message timeline -> 决策输出”的微弱生命周期。
 * 作为 STMPool 中的基本单元存储，也完全接管了原来 AgentMemory 的 chatHistory。
 */
public final class InteractionTurn {

    /** 诱发本次响应的来源（如 "player_chat", "zombie_attack"） */
    private final String triggerSource;

    /** 可选的视觉输入 */
    private final String userScreenshotBase64;

    /** 本回合内部的有序消息列表 */
    private final List<InteractionMessage> messages;

    /** 本回合真正进入模型视野的 visible contexts（仅保留 memory-eligible 条目） */
    private final List<TaggedContextEntry> visibleContexts;

    /** 本回合绑定的 supplementary extras 快照 */
    private final Map<String, String> contextExtras;

    /** 回合发生时间戳 */
    private final long timestamp;

    public InteractionTurn(String triggerSource, String userScreenshotBase64,
            List<InteractionMessage> messages,
            List<TaggedContextEntry> visibleContexts,
            Map<String, String> contextExtras) {
        this.triggerSource = triggerSource;
        this.userScreenshotBase64 = userScreenshotBase64;
        this.messages = messages == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        this.visibleContexts = Collections.unmodifiableList(
                new ArrayList<>(CollectedContextSnapshot.memoryEligibleEntries(visibleContexts)));
        this.contextExtras = contextExtras == null || contextExtras.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(contextExtras));
        this.timestamp = System.currentTimeMillis();
    }

    public String getTriggerSource() { return triggerSource; }
    public String getUserScreenshotBase64() { return userScreenshotBase64; }
    public List<InteractionMessage> getMessages() { return messages; }
    public List<TaggedContextEntry> getVisibleContexts() { return visibleContexts; }
    public Map<String, String> getContextExtras() { return contextExtras; }
    public long getTimestamp() { return timestamp; }

    /** 是否有图像输入 */
    public boolean hasImage() {
        return userScreenshotBase64 != null && !userScreenshotBase64.isEmpty();
    }

    @Override
    public String toString() {
        String preview = messages.isEmpty() ? "no_messages" : messages.get(0).toString();
        return "Turn[" + triggerSource + " | " + preview + " | messages=" + messages.size() + "]";
    }
}
