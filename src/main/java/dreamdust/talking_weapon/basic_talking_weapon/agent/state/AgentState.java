package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

/**
 * Agent 高频运行时状态：描述武器灵魂当前即时的情绪与关系数值。
 * <p>
 * 此状态更新频率高（每次触发器激活时可通过 LLM 或代码规则更新），
 * 注入到 system prompt 中影响 LLM 输出风格与情节。
 *
 * <h3>与 AgentPersona 的区别</h3>
 * <ul>
 * <li>{@code AgentState} — 高频，反映当下：情绪、熟悉度</li>
 * <li>{@link AgentPersona} — 低频，反映长期：好感度、性格词条、玩家画像</li>
 * </ul>
 */
public class AgentState {

    /**
     * 当前主导情绪（定性）。
     * 由 LLM 在启用情绪更新的触发器响应中更新。
     */
    private volatile PrimaryEmotion primaryEmotion;

    /**
     * 情绪强度（定量，0-100）。
     * 高强度值（如 >= 80）可触发 Agent 主动发言冲动。
     */
    private volatile int emotionIntensity;

    /**
     * 熟悉度（累加计数器，无上限）。
     * 由代码规则驱动：每次使用/触发时累加，反映物理意义上的"磨合次数"。
     */
    private volatile int familiarity;

    public AgentState() {
        this.primaryEmotion = PrimaryEmotion.NEUTRAL;
        this.emotionIntensity = 0;
        this.familiarity = 0;
    }

    // ── PrimaryEmotion ───────────────────────────────────────────

    public PrimaryEmotion getPrimaryEmotion() {
        return primaryEmotion;
    }

    public void setPrimaryEmotion(PrimaryEmotion primaryEmotion) {
        this.primaryEmotion = primaryEmotion != null ? primaryEmotion : PrimaryEmotion.NEUTRAL;
    }

    /**
     * 通过情绪名称字符串设置情绪（大小写不敏感）。
     * 若解析失败则保持当前情绪不变。
     *
     * @param emotionName 情绪枚举名，如 "FEARFUL"
     */
    public void setPrimaryEmotionByName(String emotionName) {
        if (emotionName == null || emotionName.isBlank())
            return;
        try {
            this.primaryEmotion = PrimaryEmotion.valueOf(emotionName.toUpperCase().trim());
        } catch (IllegalArgumentException ignored) {
            // 解析失败，保持当前情绪
        }
    }

    // ── EmotionIntensity ──────────────────────────────────────────

    public int getEmotionIntensity() {
        return emotionIntensity;
    }

    /**
     * 设置情绪强度，自动 clamped 至 [0, 100]。
     */
    public void setEmotionIntensity(int intensity) {
        this.emotionIntensity = Math.max(0, Math.min(100, intensity));
    }

    // ── Familiarity ───────────────────────────────────────────────

    public int getFamiliarity() {
        return familiarity;
    }

    public void setFamiliarity(int familiarity) {
        this.familiarity = Math.max(0, familiarity);
    }

    /**
     * 熟悉度累加（代码规则驱动，每次触发/使用时调用）。
     *
     * @param delta 增量（通常为正整数，如 +1）
     */
    public void incrementFamiliarity(int delta) {
        this.familiarity = Math.max(0, this.familiarity + delta);
    }

    // ── Prompt 注入 ───────────────────────────────────────────────

    /**
     * 生成用于注入 system prompt 的状态描述片段。
     */
    public String toPromptSnippet() {
        return "[AgentState] emotion=" + primaryEmotion.name()
                + " (intensity=" + emotionIntensity + "/100)"
                + ", familiarity=" + familiarity;
    }

    @Override
    public String toString() {
        return toPromptSnippet();
    }

    // ── PrimaryEmotion Enum ───────────────────────────────────────

    /**
     * 主导情绪枚举：描述武器灵魂当前的主要情绪倾向。
     * <p>
     * 由 LLM 在启用了 {@code EMOTION_UPDATE} 的触发器响应中更新。
     * 情绪强度由 {@link AgentState#emotionIntensity} 提供定量维度。
     */
    public enum PrimaryEmotion {
        /** 平静（默认状态） */
        NEUTRAL,
        /** 愉快，积极 */
        HAPPY,
        /** 愤怒，不满 */
        ANGRY,
        /** 兴奋，亢奋 */
        EXCITED,
        /** 无聊，漠然 */
        BORED,
        /** 恐惧，退缩 */
        FEARFUL,
        /** 悲伤，低落 */
        SAD,
        /** 好奇，探索欲强 */
        CURIOUS,
        /** 焦虑，不安 */
        ANXIOUS
    }
}
