package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

/**
 * Agent LLM 输出的结构化响应。
 * <p>
 * LLM 被要求输出一个 JSON 对象，由 {@link AgentResponseParser} 反序列化为本类实例。
 *
 * <h3>字段说明</h3>
 * <ul>
 * <li><b>thinking</b>：LLM 的推理草稿，用于辅助生成内心活动与回复。
 * 对玩家不可见，也不写入长期记忆，仅记录在日志中。</li>
 * <li><b>inner_monologue</b>：剑真正的内心活动，是剑的主观意识体验。
 * 对玩家不可见，将来会写入剑的记忆系统。</li>
 * <li><b>speech</b>：剑对持有者说出口的话。为空或省略时剑保持沉默。</li>
 * </ul>
 *
 * <pre>
 * {
 *   "thinking":         "（推理草稿，对玩家不可见）",
 *   "inner_monologue":  "（内心独白，剑的主观体验）",
 *   "speech":           "（对玩家说的话，可省略表示沉默）"
 * }
 * </pre>
 */
public final class AgentResponse {

    /** LLM 推理草稿，仅作草稿用途，不进记忆 */
    private String thinking;

    /** 剑的内心独白，将来用于记忆沉淀 */
    private String inner_monologue;

    /** 剑对持有者说出口的话；省略或空字符串表示沉默 */
    private String speech;

    // ─────────────────────────────────────────────
    // Getters / Setters（供 Gson 注入）
    // ─────────────────────────────────────────────

    public String getThinking() {
        return thinking;
    }

    public String getInnerMonologue() {
        return inner_monologue;
    }

    public String getSpeech() {
        return speech;
    }

    public void setThinking(String v) {
        this.thinking = v;
    }

    public void setInnerMonologue(String v) {
        this.inner_monologue = v;
    }

    public void setSpeech(String v) {
        this.speech = v;
    }

    // ─────────────────────────────────────────────
    // 便捷查询
    // ─────────────────────────────────────────────

    /** 是否有对外的话要说 */
    public boolean hasSpeech() {
        return speech != null && !speech.isBlank();
    }

    /** 是否有内心独白 */
    public boolean hasInnerMonologue() {
        return inner_monologue != null && !inner_monologue.isBlank();
    }

    /** 是否有推理草稿 */
    public boolean hasThinking() {
        return thinking != null && !thinking.isBlank();
    }

    @Override
    public String toString() {
        return "AgentResponse{"
                + "has_thinking=" + hasThinking()
                + ", has_monologue=" + hasInnerMonologue()
                + ", speech='" + (hasSpeech() ? speech : "(沉默)")
                + "'}";
    }
}
