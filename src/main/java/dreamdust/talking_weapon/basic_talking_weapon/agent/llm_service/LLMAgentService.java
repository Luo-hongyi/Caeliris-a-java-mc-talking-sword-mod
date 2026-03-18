package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

/**
 * LLM 服务工具类：仅负责根据配置构建内部的聊天客户端实例。
 * <p>
 * 具体的请求发送、响应处理、ActionBar 展示等逻辑已迁移至：
 * <ul>
 * <li>{@code LLMInteractionTask} — 完整的 LLM 交互流程</li>
 * <li>{@code SpeechAction} — ActionBar 文字展示</li>
 * <li>{@code AgentSensor} — 触发入口与取消</li>
 * </ul>
 */
public final class LLMAgentService {

    private LLMAgentService() {
    } // 不可实例化

    /**
     * 根据当前配置构建 OpenAI-compatible 聊天客户端。
     */
    public static LLMChatClient buildClient() {
        return new OpenAiCompatibleChatClient(
                TalkingWeaponConfig.OPENAI_API_KEY.get(),
                TalkingWeaponConfig.BASE_URL.get(),
                TalkingWeaponConfig.MODEL_NAME.get());
    }
}
