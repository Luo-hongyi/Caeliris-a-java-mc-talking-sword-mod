package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.action.SpeechAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMAgentService;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMsg;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

/**
 * 需要调用 LLM 的交互任务。
 * <p>
 * 完整流程：构建 prompt(含记忆+状态+上下文) → 调用 LLM → SpeechAction 展示 → 更新记忆。
 */
public class LLMInteractionTask extends AgentTask {

    /** 默认优先级 */
    public static final int DEFAULT_PRIORITY = 10;

    public LLMInteractionTask(AgentContext context) {
        super(DEFAULT_PRIORITY, context);
    }

    public LLMInteractionTask(int priority, AgentContext context) {
        super(priority, context);
    }

    @Override
    public void execute() {
        if (isCancelled()) return;

        AgentRuntime runtime = AgentRuntime.getInstance();
        AgentMemory memory = runtime.getMemory();
        AgentState state = runtime.getState();
        AgentContext ctx = getContext();

        try {
            // 1. 校验 API Key
            String apiKey = TalkingWeaponConfig.OPENAI_API_KEY.get();
            if (apiKey.contains("填写") || apiKey.isBlank()) {
                new SpeechAction("剑：请先在配置文件填写 API Key！").perform();
                return;
            }

            if (isCancelled()) return;

            // 2. 构建 system prompt：身份 + 状态 + 上下文
            String systemPrompt = buildSystemPrompt(memory, state, ctx);

            // ── 日志：状态与记忆 ──
            AgentLogger.logAgentState(memory, state);

            // 3. 构建 LLM 请求
            ChatLanguageModel model = LLMAgentService.buildModel();

            UserMessage message;
            String screenshotBase64 = ctx.getScreenshotBase64();
            // 从 extras 读取触发器专属 prompt，若不存在则使用通用默认值
            String userPrompt = ctx.getExtras().getOrDefault("user_prompt",
                    "请根据当前状态，以武器灵魂的身份发表一到两句话的简短评论。");

            if (screenshotBase64 != null) {
                message = UserMessage.from(
                        ImageContent.from(screenshotBase64, "image/png"),
                        TextContent.from(userPrompt)
                );
            } else {
                message = UserMessage.from(TextContent.from(userPrompt));
            }

            if (isCancelled()) return;

            // ── 日志：提示词 ──
            AgentLogger.logPrompts(systemPrompt, userPrompt, screenshotBase64 != null);

            // 4. 调用 LLM
            Response<AiMessage> response = model.generate(
                    dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                    message
            );
            String reply = response.content().text().trim();

            if (isCancelled()) return;

            // 5. 执行 SpeechAction（在 worker 线程上逐行显示）
            new SpeechAction("剑：" + reply).perform();

            // ── 日志：Agent 行动 ──
            AgentLogger.logAction("speech", reply);

            // 6. 更新记忆
            memory.addShortTerm(AgentMsg.userWithImage(userPrompt, screenshotBase64));
            memory.addShortTerm(AgentMsg.assistant(reply));

            // ── 日志结束 ──
            AgentLogger.endSession();

        } catch (Exception e) {
            if (!isCancelled()) {
                AgentLogger.logError("LLM 调用失败", e);
                AgentLogger.endSession();
                new SpeechAction("剑：我说不出话……" + e.getMessage()).perform();
            } else {
                AgentLogger.endSession();
            }
        }
    }

    @Override
    public String getTaskType() {
        return "LLMInteraction";
    }

    /**
     * 组装 system prompt：人格身份 + Agent 状态 + 当前上下文摘要。
     */
    private String buildSystemPrompt(AgentMemory memory, AgentState state, AgentContext ctx) {
        StringBuilder sb = new StringBuilder();

        // 身份
        String identity = memory.getIdentity();
        if (identity != null && !identity.isEmpty()) {
            sb.append(identity).append("\n\n");
        }

        // 长期记忆摘要
        if (!memory.getLongTerm().isEmpty()) {
            sb.append("[Long-term Memory]\n");
            for (String entry : memory.getLongTerm()) {
                sb.append("- ").append(entry).append("\n");
            }
            sb.append("\n");
        }

        // Agent 状态
        sb.append(state.toPromptSnippet()).append("\n");

        // 当前上下文
        sb.append("[Context] ").append(ctx.toString()).append("\n");

        // 扩展上下文（由各 Collector 采集）
        if (!ctx.getExtras().isEmpty()) {
            sb.append("[Extra Context]\n");
            ctx.getExtras().forEach((key, value) ->
                    sb.append("- ").append(key).append(": ").append(value).append("\n")
            );
        }

        return sb.toString();
    }
}
