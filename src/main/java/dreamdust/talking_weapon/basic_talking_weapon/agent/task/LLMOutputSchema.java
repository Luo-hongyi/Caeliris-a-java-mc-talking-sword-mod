package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import java.util.EnumSet;

/**
 * LLM 输出字段模式枚举：定义一次 LLM 调用期望返回的 JSON 字段集合。
 * <p>
 * 每个
 * {@link dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.IAgentTrigger}
 * 通过 {@code getOutputSchema()} 声明自己需要的字段子集，
 * {@code LLMInteractionTask.buildSystemPrompt()} 根据此集合动态拼装 JSON 格式指令，
 * 只要求 LLM 输出必要的字段，避免冗余信息。
 *
 * <h3>字段说明</h3>
 * <ul>
 * <li>{@link #THINKING} — 推理草稿，LLM 的"草稿纸"，不进记忆，仅日志可见</li>
 * <li>{@link #INNER_MONOLOGUE} — 剑灵内心独白，对玩家不可见，将来用于记忆沉淀</li>
 * <li>{@link #SPEECH} — 对持有者说出口的话，可为空表示沉默</li>
 * <li>{@link #EMOTION_UPDATE} — 情绪更新指令
 * {@code { "emotion": "FEARFUL", "intensity": 80 }}，
 * 仅特定触发器启用（如受伤、BOSS击杀等情绪化事件）</li>
 * </ul>
 *
 * <h3>典型配置示例</h3>
 * 
 * <pre>
 * // 普通对话（默认）
 * EnumSet.of(THINKING, INNER_MONOLOGUE, SPEECH)
 *
 * // 情绪化事件（如受伤/击杀BOSS）
 * EnumSet.of(SPEECH, EMOTION_UPDATE)
 *
 * // 仅内心活动（碎碎念）
 * EnumSet.of(THINKING, SPEECH)
 * </pre>
 */
public enum LLMOutputSchema {

    /** 推理草稿：LLM 的思考过程，不进记忆，仅记录在日志中 */
    THINKING,

    /** 数组形式的一系列行为：包含说的话、内心心理活动、法术释放、物理攻击等。 */
    ACTIONS,

    /** 情绪更新：返回新情绪名和强度值，更新 AgentState */
    EMOTION_UPDATE,

    // ── 提示词段落开关（不影响 JSON 输出格式，仅控制 system prompt 中是否注入对应段落） ──

    /** 在 system prompt 中注入 FocusTag 领域说明段落 */
    FOCUS_TAG_GUIDE,

    /** 在 system prompt 中注入 EmotionType 类型说明段落 */
    EMOTION_TYPE_GUIDE;

    /**
     * 默认 Schema：适用于大多数普通对话触发器。
     * 包含推理和具体行为动作，但不含情绪更新。
     */
    public static EnumSet<LLMOutputSchema> defaultSchema() {
        return EnumSet.of(THINKING, ACTIONS);
    }

    /**
     * 情绪感知 Schema：适用于高情绪化的触发事件。
     * 包含行动和情绪更新。
     */
    public static EnumSet<LLMOutputSchema> emotionAwareSchema() {
        return EnumSet.of(THINKING, ACTIONS, EMOTION_UPDATE, FOCUS_TAG_GUIDE, EMOTION_TYPE_GUIDE);
    }
}
