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
 * <li><b>LTMRecords</b> — 剑视角的深度记忆切片列表 / 偏好设定</li>
 * </ol>
 * <p>
 * 线程安全说明：ActionBuffer 自身线程安全；ltmRecords
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
    // Layer 1.5: STMPool (短期记忆聚合池)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 短期记忆聚合池：存放当前纪元内的全量上下文条目和 Agent 自身产出。
     * 供 LTM 合成任务读取，合成完成后清空。
     */
    private final STMPool stmPool;

    // ── ChatHistory (已废除，由 STMPool 替代) ─────────────────
    // 注意：以前的 addShortTerm, clearChatHistory 等方法已被移除，现在回合历史统归 STMPool 管理。

    // ═══════════════════════════════════════════════════════════════
    // Layer 3: LTMRecords（长期记忆切片库）
    // ═══════════════════════════════════════════════════════════════

    private final List<LTMEntry> ltmRecords;
    private int ltmRecordsCapacity;

    // ═══════════════════════════════════════════════════════════════
    // 长期记忆（压缩摘要，兼容旧流程）
    // ═══════════════════════════════════════════════════════════════

    private final List<String> longTerm;
    private int longTermCapacity;

    public AgentMemory() {
        this.identity = "";
        this.actionBuffer = new ActionBuffer(64);
        this.stmPool = new STMPool();
        this.ltmRecords = new ArrayList<>();
        this.ltmRecordsCapacity = 100;
        this.longTerm = new ArrayList<>();
        this.longTermCapacity = 50;

        // 加载外部的静态植入记忆
        try {
            List<LTMEntry> implanted = LTMStorage.loadGlobalImplantedMemories();
            this.ltmRecords.addAll(implanted);
        } catch (Exception e) {
            System.err.println("[TalkingWeapon] Failed to load implanted LTM records: " + e.getMessage());
        }
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

    public STMPool getSTMPool() {
        return stmPool;
    }

    // ── LTMRecords ────────────────────────────────────────────

    /**
     * 追加一条长期记忆 / 预设偏好。超出容量时移除最旧的（注意后续需要接入遗忘机制）。
     */
    public void addLTMRecord(LTMEntry record) {
        ltmRecords.add(record);
        while (ltmRecords.size() > ltmRecordsCapacity) {
            ltmRecords.remove(0);
        }
    }

    /**
     * 重载全局的植入记忆。清理现有记录中的 IMPLANTED 并重新读取加载。
     */
    public void reloadImplantedMemories() {
        ltmRecords.removeIf(entry -> entry.getSourceType() == LTMEntry.SourceType.IMPLANTED);
        try {
            List<LTMEntry> implanted = LTMStorage.loadGlobalImplantedMemories();
            ltmRecords.addAll(implanted);
        } catch (Exception e) {
            System.err.println("[TalkingWeapon] Failed to reload implanted LTM records: " + e.getMessage());
        }
    }

    public List<LTMEntry> getLtmRecords() {
        return Collections.unmodifiableList(ltmRecords);
    }

    public int getLtmRecordsCapacity() {
        return ltmRecordsCapacity;
    }

    public void setLtmRecordsCapacity(int capacity) {
        this.ltmRecordsCapacity = capacity;
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

        for (InteractionTurn turn : stmPool.turnSnapshot()) {
            // 用户输入：仅当为 player_chat 时才作为 USER 角色进入历史，
            // 其他触发源的 prompt 属于系统事件指令，应在 system prompt 的事件段落中体现。
            if (turn.getUserPrompt() != null && !turn.getUserPrompt().isEmpty()) {
                if ("player_chat".equals(turn.getTriggerSource())) {
                    if (turn.hasImage()) {
                        messages.add(AgentMsg.userWithImage(turn.getUserPrompt(), turn.getUserScreenshotBase64()));
                    } else {
                        messages.add(AgentMsg.user(turn.getUserPrompt()));
                    }
                }
            }
            
            // 剑灵行动（SPEECH / INNER_MONOLOGUE 等都以 assistant 角色进入历史，地位平等）
            for (AgentOutputRecord record : turn.getAgentActions()) {
                if (record.getText() == null || record.getText().isEmpty())
                    continue;
                messages.add(AgentMsg.assistant("[" + record.getType().name() + "] " + record.getText()));
            }
        }
        return messages;
    }

    @Override
    public String toString() {
        return "AgentMemory{identity=" + (identity.length() > 30 ? identity.substring(0, 30) + "..." : identity)
                + ", actionBuffer=" + actionBuffer.size()
                + ", stmPool=" + stmPool
                + ", ltmRecords=" + ltmRecords.size()
                + ", longTerm=" + longTerm.size() + "}";
    }
}
