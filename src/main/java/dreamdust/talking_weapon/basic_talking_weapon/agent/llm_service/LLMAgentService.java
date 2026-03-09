package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * LLM 服务工具类：仅负责根据配置构建 {@link ChatLanguageModel} 实例。
 * <p>
 * 具体的请求发送、响应处理、ActionBar 展示等逻辑已迁移至：
 * <ul>
 *   <li>{@code LLMInteractionTask} — 完整的 LLM 交互流程</li>
 *   <li>{@code SpeechAction} — ActionBar 文字展示</li>
 *   <li>{@code AgentSensor} — 触发入口与取消</li>
 * </ul>
 */
public final class LLMAgentService {

    private LLMAgentService() {} // 不可实例化

    /**
     * 根据当前配置构建 {@link ChatLanguageModel}。
     * <p>
     * 所有 Provider 均通过 OpenAI 兼容接口调用：
     * <ul>
     *   <li>OpenAI: https://api.openai.com/v1/</li>
     *   <li>Qwen: https://dashscope.aliyuncs.com/compatible-mode/v1/</li>
     *   <li>Claude / Gemini: 通过第三方代理</li>
     * </ul>
     */
    public static ChatLanguageModel buildModel() {
        return OpenAiChatModel.builder()
                .apiKey(TalkingWeaponConfig.OPENAI_API_KEY.get())
                .baseUrl(TalkingWeaponConfig.BASE_URL.get())
                .modelName(TalkingWeaponConfig.MODEL_NAME.get())
                .maxTokens(TalkingWeaponConfig.MAX_TOKENS.get())
                .build();
    }
}
