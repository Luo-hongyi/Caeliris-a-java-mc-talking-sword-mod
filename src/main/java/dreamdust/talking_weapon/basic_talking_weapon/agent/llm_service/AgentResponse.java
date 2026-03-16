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
 *   "actions": [
 *     { "type": "inner_monologue", "content": "（内心独白，剑的主观体验）" },
 *     { "type": "speech", "content": "（对玩家说的话）" }
 *   ]
 * }
 * </pre>
 */
public final class AgentResponse {

    /** LLM 推理草稿，仅作草稿用途，不进记忆 */
    private String thinking;

    /** 动作列表：平等的包容所有发言、思考以及未来的扩充动作 */
    private java.util.List<ActionRequest> actions = new java.util.ArrayList<>();

    /**
     * 情绪更新指令（可选）。
     * 仅当触发器的 OutputSchema 包含 {@code EMOTION_UPDATE} 时，LLM 才被要求填写此字段。
     * Gson 自动反序列化，字段缺失时为 null。
     */
    private EmotionUpdate emotion_update;

    /**
     * LLM 返回的情绪更新指令。
     */
    public static class EmotionUpdate {
        /** 情绪枚举名，大写，如 "FEARFUL". 对应 AgentState.PrimaryEmotion */
        public String emotion;
        /** 情绪强度，0-100 */
        public int intensity;

        /** 是否有效（emotion 字段非空） */
        public boolean isValid() {
            return emotion != null && !emotion.isBlank();
        }

        @Override
        public String toString() {
            return "EmotionUpdate{emotion='" + emotion + "', intensity=" + intensity + "}";
        }
    }

    /**
     * 长期记忆合成（可选）。
     * 仅用于 ReflectionTask 周期总结。
     */
    public static class LtmSynthesis {
        public String description;
        public java.util.List<String> focus_tags;
        public java.util.List<String> detail_tags;
        public float importance = 0.5f;

        public boolean isValid() {
            return description != null && !description.isBlank();
        }

        @Override
        public String toString() {
            return "LtmSynthesis{desc='" + description + "', importance=" + importance + "}";
        }
    }

    private LtmSynthesis ltm_synthesis;

    /**
     * Agent 动作请求。
     */
    public static class ActionRequest {
        public String type;
        public String content;
        
        /** 用于 favorability_update 类型动作的特定金额字段 */
        public Integer change_amount;

        public ActionRequest() {}

        public ActionRequest(String type, String content) {
            this.type = type;
            this.content = content;
        }

        @Override
        public String toString() {
            return "ActionRequest{type='" + type + "', content='" + content + "'}";
        }
    }

    // ─────────────────────────────────────────────
    // Getters / Setters（供 Gson 注入）
    // ─────────────────────────────────────────────

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String v) {
        this.thinking = v;
    }

    public java.util.List<ActionRequest> getActions() {
        if (actions == null) {
            actions = new java.util.ArrayList<>();
        }
        return actions;
    }

    public void setActions(java.util.List<ActionRequest> actions) {
        this.actions = actions;
    }

    public EmotionUpdate getEmotionUpdate() {
        return emotion_update;
    }

    public void setEmotionUpdate(EmotionUpdate v) {
        this.emotion_update = v;
    }

    public LtmSynthesis getLtmSynthesis() {
        return ltm_synthesis;
    }

    public void setLtmSynthesis(LtmSynthesis v) {
        this.ltm_synthesis = v;
    }

    // ─────────────────────────────────────────────
    // 便捷查询
    // ─────────────────────────────────────────────

    /** 是否有推理草稿 */
    public boolean hasThinking() {
        return thinking != null && !thinking.isBlank();
    }

    /** 是否携带情绪更新 */
    public boolean hasEmotionUpdate() {
        return emotion_update != null && emotion_update.isValid();
    }

    /** 是否携带LTM摘要 */
    public boolean hasLtmSynthesis() {
        return ltm_synthesis != null && ltm_synthesis.isValid();
    }

    /** 是否有非空的动作列表 */
    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }

    @Override
    public String toString() {
        return "AgentResponse{"
                + "has_thinking=" + hasThinking()
                + ", actions=" + getActions().size()
                + "}";
    }
}
