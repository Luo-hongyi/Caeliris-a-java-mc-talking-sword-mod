package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 记忆系统：三池架构。
 * <ol>
 * <li><b>Identity</b> — 人格身份 (system prompt)，永久不变</li>
 * <li><b>ActionBuffer</b> — 环形动作流，高频轻量，不触发 LLM</li>
 * <li><b>ChatHistory</b> — 对话消息滑动窗口（保留 {@link AgentMsg}）</li>
 * <li><b>MemoryRecords</b> — 剑视角的深度记忆切片列表</li>
 * </ol>
 * <p>
 * 线程安全说明：ActionBuffer 自身线程安全；chatHistory 和 memoryRecords
 * 的访问集中在 AgentRuntime 的 worker 线程中，调用方确保不并发写。
 */
public class AgentMemory {

    // ═══════════════════════════════════════════════════════════════
    // Layer 0: Identity（永久）
    // ═══════════════════════════════════════════════════════════════

    private String identity;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1: ActionBuffer（环形动作流）
    // ═══════════════════════════════════════════════════════════════

    private final ActionBuffer actionBuffer;

    // ═══════════════════════════════════════════════════════════════
    // Layer 1.5: ConsumedActions (已读动作池)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 存放已经被 RecentActionCollector 读取并喂给 LLM 的动作记录。
     * 后续供 ReflectionTask（反思任务）或后台统计提取，防止阅后即焚导致彻底丢失。
     */
    private final ActionBuffer consumedActions;

    // ═══════════════════════════════════════════════════════════════
    // Layer 2: ChatHistory（对话消息滑动窗口）
    // ═══════════════════════════════════════════════════════════════

    private final List<AgentMsg> chatHistory;
    private int chatHistoryCapacity;

    // ═══════════════════════════════════════════════════════════════
    // Layer 3: MemoryRecords（记忆切片库）
    // ═══════════════════════════════════════════════════════════════

    private final List<MemoryRecord> memoryRecords;
    private int memoryRecordsCapacity;

    // ═══════════════════════════════════════════════════════════════
    // 长期记忆（压缩摘要，兼容旧流程）
    // ═══════════════════════════════════════════════════════════════

    private final List<String> longTerm;
    private int longTermCapacity;

    public AgentMemory() {
        this.identity = "";
        this.actionBuffer = new ActionBuffer(64);
        this.consumedActions = new ActionBuffer(256); // 已读池容量大一些
        this.chatHistory = new ArrayList<>();
        this.chatHistoryCapacity = 20;
        this.memoryRecords = new ArrayList<>();
        this.memoryRecordsCapacity = 100;
        this.longTerm = new ArrayList<>();
        this.longTermCapacity = 50;
    }

    // ── Identity ─────────────────────────────────────────────────

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    // ── ActionBuffer ─────────────────────────────────────────────

    public ActionBuffer getActionBuffer() {
        return actionBuffer;
    }

    public ActionBuffer getConsumedActions() {
        return consumedActions;
    }

    // ── ChatHistory ──────────────────────────────────────────────

    /**
     * 追加一条消息到对话历史。超出容量时移除最旧的。
     */
    public void addChatMessage(AgentMsg msg) {
        chatHistory.add(msg);
        while (chatHistory.size() > chatHistoryCapacity) {
            chatHistory.remove(0);
        }
    }

    /** 旧 API 兼容：addShortTerm → addChatMessage */
    public void addShortTerm(AgentMsg msg) {
        addChatMessage(msg);
    }

    public List<AgentMsg> getChatHistory() {
        return Collections.unmodifiableList(chatHistory);
    }

    /** 旧 API 兼容 */
    public List<AgentMsg> getShortTerm() {
        return getChatHistory();
    }

    public void clearChatHistory() {
        chatHistory.clear();
    }

    public int getChatHistoryCapacity() {
        return chatHistoryCapacity;
    }

    public void setChatHistoryCapacity(int capacity) {
        this.chatHistoryCapacity = capacity;
    }

    /** 旧 API 兼容 */
    public int getShortTermCapacity() {
        return chatHistoryCapacity;
    }

    public void setShortTermCapacity(int capacity) {
        this.chatHistoryCapacity = capacity;
    }

    // ── MemoryRecords ────────────────────────────────────────────

    /**
     * 追加一条记忆切片。超出容量时移除最旧的。
     */
    public void addMemoryRecord(MemoryRecord record) {
        memoryRecords.add(record);
        while (memoryRecords.size() > memoryRecordsCapacity) {
            memoryRecords.remove(0);
        }
    }

    public List<MemoryRecord> getMemoryRecords() {
        return Collections.unmodifiableList(memoryRecords);
    }

    public int getMemoryRecordsCapacity() {
        return memoryRecordsCapacity;
    }

    public void setMemoryRecordsCapacity(int capacity) {
        this.memoryRecordsCapacity = capacity;
    }

    // ── Long-term（兼容旧流程） ──────────────────────────────────

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

    public int getLongTermCapacity() {
        return longTermCapacity;
    }

    public void setLongTermCapacity(int capacity) {
        this.longTermCapacity = capacity;
    }

    // ── Utility ──────────────────────────────────────────────────

    /**
     * 构建发送给 LLM 的完整消息列表：identity → long-term 摘要 → chat history。
     */
    public List<AgentMsg> buildPromptMessages() {
        List<AgentMsg> messages = new ArrayList<>();

        if (identity != null && !identity.isEmpty()) {
            messages.add(AgentMsg.system(identity));
        }

        if (!longTerm.isEmpty()) {
            StringBuilder sb = new StringBuilder("[Long-term Memory]\n");
            for (String entry : longTerm) {
                sb.append("- ").append(entry).append("\n");
            }
            messages.add(AgentMsg.system(sb.toString()));
        }

        messages.addAll(chatHistory);
        return messages;
    }

    @Override
    public String toString() {
        return "AgentMemory{identity=" + (identity.length() > 30 ? identity.substring(0, 30) + "..." : identity)
                + ", actionBuffer=" + actionBuffer.size()
                + ", chatHistory=" + chatHistory.size()
                + ", memoryRecords=" + memoryRecords.size()
                + ", longTerm=" + longTerm.size() + "}";
    }
}
