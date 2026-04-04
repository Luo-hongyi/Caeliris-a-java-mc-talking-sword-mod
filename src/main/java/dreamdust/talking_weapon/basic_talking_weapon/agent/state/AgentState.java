package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

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

    /**
     * 阶段性前情提要。
     * <p>
     * 这不是“绝对当前时刻”的即时状态，而是由 reflection 基于过去一段 episode
     * 提炼出的阶段性摘要，用于帮助后续交互理解最近的大背景。
     */
    private volatile String recap;

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
     * 当前主要作为联想采样的“温度”使用：
     * 值越低，越偏向稳定地选择高分候选；
     * 值越高，越容易从次高分候选中抽到意外联想。
     */
    private volatile float wanderingMind;

    /**
     * 忆旧值（0.0 ~ 2.0）。
     * <p>
     * 当前为预留状态参数，暂未接入 recall 公式。
     */
    private volatile float nostalgia;

    /**
     * 召回预设：统一模型上的一组参数预设。
     *
     * @see RecallPreset
     */
    private volatile RecallPreset recallPreset;

    /**
     * CUSTOM 召回预设的手动调参。
     * <p>
     * 这组值会持久化到 `agent_state.json.state.recall_tuning`，由游戏内 State Screen 与
     * Studio 共用；当 authored 文件缺失时，会回退到客户端配置中的默认值。
     */
    private volatile RecallTuning recallTuning;

    /**
     * 是否启用“植入记忆保底”。
     * <p>
     * 打开后，当本轮正常 recall 没有命中任何“无绝对时间锚点”的植入记忆
     * （当前实现上表现为 `occurredGameTime < 0`）时，
     * 系统会额外复用本轮 cue 池，对这类植入记忆做一次轻量补捞。
     */
    private volatile boolean implantedMemorySafetyNetEnabled;

    /**
     * 是否额外写出全量原始动作日志。
     * <p>
     * 该日志主要用于调试 ActionArchive / fixed segment 封装过程，
     * 默认关闭，避免在交互触发时制造额外文本 I/O。
     */
    private volatile boolean fullRawActionLogEnabled;

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
        this.recallPreset = RecallPreset.HYBRID;
        this.recallTuning = RecallTuning.defaults();
        this.implantedMemorySafetyNetEnabled = false;
        this.fullRawActionLogEnabled = false;
        this.currentDesireTag = null;
        this.desireDescription = "";
        this.playerAddress = "主人";
        this.recap = "";
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

    // ── Recap ────────────────────────────────────────────────────────

    public String getRecap() {
        return recap != null ? recap : "";
    }

    public void setRecap(String recap) {
        this.recap = recap != null ? recap.trim() : "";
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

    /** 设置忆旧值，自动 clamped 至 [0.0, 2.0]。 */
    public void setNostalgia(float value) {
        this.nostalgia = Math.max(0f, Math.min(2f, value));
    }

    // ── RecallPreset ──────────────────────────────────────────────────

    public RecallPreset getRecallPreset() {
        return recallPreset;
    }

    public void setRecallPreset(RecallPreset preset) {
        this.recallPreset = preset != null ? preset : RecallPreset.HYBRID;
    }

    public void setRecallPresetByName(String name) {
        if (name == null || name.isBlank())
            return;
        try {
            this.recallPreset = RecallPreset.valueOf(name.toUpperCase().trim());
        } catch (IllegalArgumentException ignored) {
        }
    }

    public RecallTuning getRecallTuning() {
        return recallTuning != null ? recallTuning : RecallTuning.defaults();
    }

    public void setRecallTuning(RecallTuning tuning) {
        this.recallTuning = tuning != null ? tuning : RecallTuning.defaults();
    }

    public boolean isImplantedMemorySafetyNetEnabled() {
        return implantedMemorySafetyNetEnabled;
    }

    public void setImplantedMemorySafetyNetEnabled(boolean enabled) {
        this.implantedMemorySafetyNetEnabled = enabled;
    }

    public boolean isFullRawActionLogEnabled() {
        return fullRawActionLogEnabled;
    }

    public void setFullRawActionLogEnabled(boolean enabled) {
        this.fullRawActionLogEnabled = enabled;
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
                .append(", recallPreset=").append(recallPreset != null ? recallPreset.name() : "HYBRID")
                .append(", 对玩家称呼=").append(getPlayerAddress());
        if (!getRecap().isBlank()) {
            sb.append("\n阶段前情提要: ").append(getRecap());
        }

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
     * 召回预设：统一记忆模型上的参数打包。
     *
     * <ul>
     * <li>{@link #SEED_FIRST} — 更稀疏，更像被少数强 cue 点燃</li>
     * <li>{@link #FULL_VISIBILITY} — 更积极，强调当前场景对记忆的命中反馈</li>
     * <li>{@link #HYBRID} — 默认模式，强调发酵层稳定与适度联想</li>
     * <li>{@link #CUSTOM} — 具体参数读取 `agent_state.json.state.recall_tuning`</li>
     * </ul>
     */
    public enum RecallPreset {
        SEED_FIRST,
        FULL_VISIBILITY,
        HYBRID,
        CUSTOM
    }

    /**
     * CUSTOM 召回预设参数。
     * <p>
     * 这些值分别控制：
     * <ul>
     * <li>主召回阶段，本轮最多提升多少条候选记忆</li>
     * <li>sandbox 联想阶段，本轮最多投放多少个联想 cue</li>
     * <li>本轮最多允许多少条记忆进入表层意识</li>
     * <li>retained fermentation 层最多保留多少条候选记忆</li>
     * </ul>
     */
    public static final class RecallTuning {
        public static final int MIN_MAIN_CANDIDATES_PER_CUE = 1;
        public static final int MAX_MAIN_CANDIDATES_PER_CUE = 32;
        public static final int MIN_SANDBOX_CUES_PER_MEMORY = 1;
        public static final int MAX_SANDBOX_CUES_PER_MEMORY = 32;
        public static final int MIN_CONSCIOUS_MEMORY_CAP = 1;
        public static final int MAX_CONSCIOUS_MEMORY_CAP = 16;
        public static final int MIN_RETENTION_MEMORY_CAP = 1;
        public static final int MAX_RETENTION_MEMORY_CAP = 16;

        private final int mainCandidatesPerCue;
        private final int sandboxCuesPerMemory;
        private final int consciousMemoryCap;
        private final int retentionMemoryCap;

        public RecallTuning(int mainCandidatesPerCue, int sandboxCuesPerMemory, int consciousMemoryCap,
                int retentionMemoryCap) {
            this.mainCandidatesPerCue = clampInt(
                    mainCandidatesPerCue,
                    MIN_MAIN_CANDIDATES_PER_CUE,
                    MAX_MAIN_CANDIDATES_PER_CUE);
            this.sandboxCuesPerMemory = clampInt(
                    sandboxCuesPerMemory,
                    MIN_SANDBOX_CUES_PER_MEMORY,
                    MAX_SANDBOX_CUES_PER_MEMORY);
            this.consciousMemoryCap = clampInt(
                    consciousMemoryCap,
                    MIN_CONSCIOUS_MEMORY_CAP,
                    MAX_CONSCIOUS_MEMORY_CAP);
            this.retentionMemoryCap = clampInt(
                    retentionMemoryCap,
                    MIN_RETENTION_MEMORY_CAP,
                    MAX_RETENTION_MEMORY_CAP);
        }

        public static RecallTuning defaults() {
            return new RecallTuning(
                    TalkingWeaponConfig.LTM_RECALL_MAIN_CANDIDATES_PER_CUE.get(),
                    TalkingWeaponConfig.LTM_RECALL_SANDBOX_CUES_PER_MEMORY.get(),
                    TalkingWeaponConfig.LTM_RECALL_CONSCIOUS_MEMORY_CAP.get(),
                    TalkingWeaponConfig.LTM_RECALL_RETENTION_MEMORY_CAP.get());
        }

        public int getMainCandidatesPerCue() {
            return mainCandidatesPerCue;
        }

        public int getSandboxCuesPerMemory() {
            return sandboxCuesPerMemory;
        }

        public int getConsciousMemoryCap() {
            return consciousMemoryCap;
        }

        public int getRetentionMemoryCap() {
            return retentionMemoryCap;
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
