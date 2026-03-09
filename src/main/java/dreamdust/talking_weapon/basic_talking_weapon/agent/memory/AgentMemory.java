package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 三层记忆系统：
 * <ol>
 *   <li><b>Identity</b> — 人格身份 (system prompt)，永久不变</li>
 *   <li><b>Short-term</b> — 近期对话窗口，固定大小滑动窗口</li>
 *   <li><b>Long-term</b> — 语义长期记忆，由 ReflectionTask 压缩写入</li>
 * </ol>
 */
public class AgentMemory {

    /** 身份层：不可变的人格 system prompt */
    private String identity;

    /** 短期记忆窗口：最近 N 条对话 */
    private final List<AgentMsg> shortTerm;

    /** 短期记忆窗口最大容量 */
    private int shortTermCapacity;

    /** 长期记忆：压缩后的语义摘要列表 */
    private final List<String> longTerm;

    /** 长期记忆最大条目数 */
    private int longTermCapacity;

    public AgentMemory() {
        this.identity = "";
        this.shortTerm = new ArrayList<>();
        this.shortTermCapacity = 20;
        this.longTerm = new ArrayList<>();
        this.longTermCapacity = 50;
    }

    // ---- Identity Layer ----

    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }

    // ---- Short-term Layer ----

    /**
     * 追加一条消息到短期记忆。超出容量时移除最旧的。
     */
    public void addShortTerm(AgentMsg msg) {
        shortTerm.add(msg);
        while (shortTerm.size() > shortTermCapacity) {
            shortTerm.remove(0);
        }
    }

    public List<AgentMsg> getShortTerm() {
        return Collections.unmodifiableList(shortTerm);
    }

    public void clearShortTerm() {
        shortTerm.clear();
    }

    public int getShortTermCapacity() { return shortTermCapacity; }
    public void setShortTermCapacity(int capacity) { this.shortTermCapacity = capacity; }

    // ---- Long-term Layer ----

    /**
     * 追加一条压缩摘要到长期记忆。
     */
    public void addLongTerm(String summary) {
        longTerm.add(summary);
        while (longTerm.size() > longTermCapacity) {
            longTerm.remove(0);
        }
    }

    public List<String> getLongTerm() {
        return Collections.unmodifiableList(longTerm);
    }

    public void clearLongTerm() {
        longTerm.clear();
    }

    public int getLongTermCapacity() { return longTermCapacity; }
    public void setLongTermCapacity(int capacity) { this.longTermCapacity = capacity; }

    // ---- Utility ----

    /**
     * 构建发送给 LLM 的完整消息列表：identity → long-term 摘要 → short-term 对话窗口。
     */
    public List<AgentMsg> buildPromptMessages() {
        List<AgentMsg> messages = new ArrayList<>();

        // 1. Identity
        if (identity != null && !identity.isEmpty()) {
            messages.add(AgentMsg.system(identity));
        }

        // 2. Long-term summaries as system context
        if (!longTerm.isEmpty()) {
            StringBuilder sb = new StringBuilder("[Long-term Memory]\n");
            for (String entry : longTerm) {
                sb.append("- ").append(entry).append("\n");
            }
            messages.add(AgentMsg.system(sb.toString()));
        }

        // 3. Short-term conversation window
        messages.addAll(shortTerm);

        return messages;
    }

    @Override
    public String toString() {
        return "AgentMemory{identity=" + (identity.length() > 30 ? identity.substring(0, 30) + "..." : identity)
                + ", shortTerm=" + shortTerm.size()
                + ", longTerm=" + longTerm.size() + "}";
    }
}
