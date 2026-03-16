package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;

/**
 * Agent 高频运行时状态：描述武器灵魂当前即时的情绪、关系数值与思维特性。
 * <p>
 * 此状态更新频率高（每次触发器激活时可通过 LLM 或代码规则更新），
 * 注入到 system prompt 中影响 LLM 输出风格与情节。
 *
 * <h3>与 AgentPersona 的区别</h3>
 * <ul>
 * <li>{@code AgentState} — 高频，反映当下：情绪、熟悉度、思维模式</li>
 * <li>{@link AgentPersona} — 低频，反映长期：好感度、性格词条、玩家画像</li>
 * </ul>
 */
public class AgentState {

    // ═══════════════════════════════════════════════════════════
    // 情绪模块（高频，由 LLM 更新）
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // 关系数值（混合：熟悉度由代码累加，以下由此处存放）
    // ═══════════════════════════════════════════════════════════

    /**
     * 熟悉度（累加计数器，无上限）。
     * 由代码规则驱动：每次使用/触发时累加，反映物理意义上的"磨合次数"。
     */
    private volatile int familiarity;

    /**
     * 对玩家的称呼（如 "主人", "朋友"）。
     */
    private volatile String playerAddress;

    // ═══════════════════════════════════════════════════════════
    // 思维特性（低中频，可在设置界面或 LLM 更新）
    // ═══════════════════════════════════════════════════════════

    /**
     * 专注度（0.0 ~ 1.0）。
     * 影响注意力过滤器：专注度越高，剑的主观喜好影响越大。
     */
    private volatile float concentration;

    /**
     * 随想值（0.0 ~ 1.0）。
     * <p>
     * 每次构建上下文时，以此概率随机注入一条与当前场景无关的
     * 性格词条或历史记忆，制造思维跳脱的生动感。
     * 默认 0.1（10% 概率开小差）。
     */
    private volatile float wanderingMind;

    /**
     * 忆旧值（0.0 ~ 1.0）。
     * <p>
     * 越高越容易让久远记忆保留影响力；越低越偏向新近经历。
     */
    private volatile float nostalgia;

    /**
     * 联想模式：控制记忆和性格词条召回的深度与广度。
     *
     * @see AssociationMode
     */
    private volatile AssociationMode associationMode;

    /**
     * 当前渴望关联的认知领域（可为 null，表示无特定执念）。
     * <p>
     * 当此 Tag 与当前活跃场景 Tag 不符时，
     * LLM 会被引导在回复中表现出隐约的压抑感或牢骚。
     */
    private volatile FocusTag currentDesireTag;

    /**
     * 渴望的自然语言描述（与 currentDesireTag 配套）。
     * 例如："非常想痛饮怪物的血"
     */
    private volatile String desireDescription;

    // ── 构造 ─────────────────────────────────────────────────────────

    public AgentState() {
        this.primaryEmotion = PrimaryEmotion.NEUTRAL;
        this.emotionIntensity = 0;
        this.familiarity = 0;
        this.concentration = 0.5f;
        this.wanderingMind = 0.1f;
        this.nostalgia = 0.5f;
        this.associationMode = AssociationMode.BALANCED;
        this.currentDesireTag = null;
        this.desireDescription = "";
        this.playerAddress = "主人";
    }

    // ── PrimaryEmotion ───────────────────────────────────────────────

    public PrimaryEmotion getPrimaryEmotion() {
        return primaryEmotion;
    }

    public void setPrimaryEmotion(PrimaryEmotion primaryEmotion) {
        this.primaryEmotion = primaryEmotion != null ? primaryEmotion : PrimaryEmotion.NEUTRAL;
    }

    /**
     * 通过情绪名称字符串设置情绪（大小写不敏感）。
     * 若解析失败则保持当前情绪不变。
     */
    public void setPrimaryEmotionByName(String emotionName) {
        if (emotionName == null || emotionName.isBlank())
            return;
        try {
            this.primaryEmotion = PrimaryEmotion.valueOf(emotionName.toUpperCase().trim());
        } catch (IllegalArgumentException ignored) {
        }
    }

    // ── EmotionIntensity ──────────────────────────────────────────────

    public int getEmotionIntensity() {
        return emotionIntensity;
    }

    /** 设置情绪强度，自动 clamped 至 [0, 100]。 */
    public void setEmotionIntensity(int intensity) {
        this.emotionIntensity = Math.max(0, Math.min(100, intensity));
    }

    // ── Familiarity ───────────────────────────────────────────────────

    public int getFamiliarity() {
        return familiarity;
    }

    public void setFamiliarity(int familiarity) {
        this.familiarity = Math.max(0, familiarity);
    }

    /** 熟悉度累加（代码规则驱动，每次触发/使用时调用）。 */
    public void incrementFamiliarity(int delta) {
        this.familiarity = Math.max(0, this.familiarity + delta);
    }

    // ── PlayerAddress ─────────────────────────────────────────────────

    public String getPlayerAddress() {
        return playerAddress != null && !playerAddress.isBlank() ? playerAddress : "主人";
    }

    public void setPlayerAddress(String address) {
        this.playerAddress = address != null && !address.isBlank() ? address.trim() : "主人";
    }

    // ── Concentration ──────────────────────────────────────────────────

    public float getConcentration() {
        return concentration;
    }

    /** 设置专注度，自动 clamped 至 [0.0, 1.0]。 */
    public void setConcentration(float value) {
        this.concentration = Math.max(0f, Math.min(1f, value));
    }

    // ── WanderingMind ─────────────────────────────────────────────────

    public float getWanderingMind() {
        return wanderingMind;
    }

    /** 设置随想值，自动 clamped 至 [0.0, 1.0]。 */
    public void setWanderingMind(float value) {
        this.wanderingMind = Math.max(0f, Math.min(1f, value));
    }

    // ── Nostalgia ──────────────────────────────────────────────────────

    public float getNostalgia() {
        return nostalgia;
    }

    /** 设置忆旧值，自动 clamped 至 [0.0, 1.0]。 */
    public void setNostalgia(float value) {
        this.nostalgia = Math.max(0f, Math.min(1f, value));
    }

    // ── AssociationMode ───────────────────────────────────────────────

    public AssociationMode getAssociationMode() {
        return associationMode;
    }

    public void setAssociationMode(AssociationMode mode) {
        this.associationMode = mode != null ? mode : AssociationMode.BALANCED;
    }

    public void setAssociationModeByName(String name) {
        if (name == null || name.isBlank())
            return;
        try {
            this.associationMode = AssociationMode.valueOf(name.toUpperCase().trim());
        } catch (IllegalArgumentException ignored) {
        }
    }

    // ── CurrentDesire ─────────────────────────────────────────────────

    public FocusTag getCurrentDesireTag() {
        return currentDesireTag;
    }

    public void setCurrentDesireTag(FocusTag tag) {
        this.currentDesireTag = tag;
    }

    public String getDesireDescription() {
        return desireDescription != null ? desireDescription : "";
    }

    public void setDesireDescription(String desc) {
        this.desireDescription = desc != null ? desc : "";
    }

    // ── Prompt 注入 ───────────────────────────────────────────────────

    /**
     * 生成用于注入 system prompt 的状态描述片段。
     */
    public String toPromptSnippet() {
        StringBuilder sb = new StringBuilder();
        sb.append("[AgentState] emotion=").append(primaryEmotion.name())
                .append(" (intensity=").append(emotionIntensity).append("/100)")
                .append(", familiarity=").append(familiarity)
                .append(", concentration=").append(String.format("%.2f", concentration))
                .append(", wandering=").append(String.format("%.2f", wanderingMind))
                .append(", nostalgia=").append(String.format("%.2f", nostalgia))
                .append(", associationMode=").append(associationMode != null ? associationMode.name() : "BALANCED")
                .append(", 对玩家称呼=").append(getPlayerAddress());

        return sb.toString();
    }

    @Override
    public String toString() {
        return toPromptSnippet();
    }

    // ═══════════════════════════════════════════════════════════
    // 内部枚举
    // ═══════════════════════════════════════════════════════════

    /**
     * 主导情绪枚举：描述武器灵魂当前的主要情绪倾向。
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

    /**
     * 联想模式：决定记忆与性格词条召回的深度和每层宽度。
     *
     * <ul>
     * <li>{@link #DIVERGENT} — 发散：从更多当前条目起跳，浅层铺开</li>
     * <li>{@link #BALANCED} — 均衡：少量起点，有限追溯</li>
     * <li>{@link #DEEP} — 深度：少量起点，沿更深层联想链追溯</li>
     * <li>{@link #CUSTOM} — 自定义：具体参数读取配置文件</li>
     * </ul>
     */
    public enum AssociationMode {
        /** 发散：从更多当前条目起跳，快速铺开少数回忆 */
        DIVERGENT(0, 4),
        /** 均衡：默认模式，做少量层级联想 */
        BALANCED(1, 2),
        /** 深度：少量起点，但更愿意沿单条链深入 */
        DEEP(2, 1),
        /** 自定义：召回参数完全由配置文件控制 */
        CUSTOM(0, 0);

        /** 最大联想深度（层数） */
        public final int maxDepth;
        /** 每层最多召回条数 */
        public final int widthPerLayer;

        AssociationMode(int maxDepth, int widthPerLayer) {
            this.maxDepth = maxDepth;
            this.widthPerLayer = widthPerLayer;
        }
    }
}
