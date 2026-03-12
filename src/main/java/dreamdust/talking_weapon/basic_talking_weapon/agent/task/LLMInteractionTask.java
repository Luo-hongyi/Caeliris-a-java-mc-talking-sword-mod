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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需要调用 LLM 的交互任务。
 * <p>
 * 完整流程：构建 prompt(含记忆+状态+上下文) → 调用 LLM → SpeechAction 展示 → 更新记忆。
 */
public class LLMInteractionTask extends AgentTask {

    /** 默认优先级 */
    public static final int DEFAULT_PRIORITY = 10;

    /** 匹配 &lt;think&gt;...&lt;/think&gt; 标签（支持跨行、贪婪匹配） */
    private static final Pattern THINK_PATTERN = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);

    public LLMInteractionTask(AgentContext context) {
        super(DEFAULT_PRIORITY, context);
    }

    public LLMInteractionTask(int priority, AgentContext context) {
        super(priority, context);
    }

    @Override
    public void execute() {
        if (isCancelled())
            return;

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

            if (isCancelled())
                return;

            // 1.5. 从配置读取人格身份写入记忆
            String configIdentity = TalkingWeaponConfig.SYSTEM_IDENTITY.get();
            if (configIdentity != null && !configIdentity.isBlank()) {
                memory.setIdentity(configIdentity);
            }

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
                        TextContent.from(userPrompt));
            } else {
                message = UserMessage.from(TextContent.from(userPrompt));
            }

            if (isCancelled())
                return;

            // ── 日志：提示词 ──
            AgentLogger.logPrompts(systemPrompt, userPrompt, screenshotBase64 != null);

            // 4. 调用 LLM
            Response<AiMessage> response = model.generate(
                    dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                    message);
            String rawReply = response.content().text().trim();
            AgentLogger.logRawResponse(rawReply);

            if (isCancelled())
                return;

            // 4.5. 解析思考内容（根据触发器类型决定是否启用思考）
            boolean thinkingEnabled = isThinkingEnabled(ctx.getTriggerSource());
            String thinking = null;
            String reply = rawReply;

            if (thinkingEnabled) {
                // 匹配 <think>...</think> 标签中的思考内容
                Matcher m = THINK_PATTERN.matcher(rawReply);
                if (m.find()) {
                    thinking = m.group(1).trim();
                    // 移除所有 <think>...</think> 块，取剩余部分作为回复
                    reply = m.replaceAll("").trim();
                }
                // 如果解析后回复为空，使用原始回复
                if (reply.isEmpty()) {
                    reply = rawReply;
                    thinking = null;
                }
            }

            // 5. 执行 SpeechAction（在 worker 线程上逐行显示，仅显示回复部分）
            new SpeechAction("剑：" + reply).perform();

            // ── 日志：思考内容（仅在启用思考且有思考时记录，超长截断） ──
            if (thinking != null && !thinking.isEmpty()) {
                int maxLen = TalkingWeaponConfig.THINKING_MAX_LENGTH.get();
                if (maxLen > 0 && thinking.length() > maxLen) {
                    thinking = thinking.substring(0, maxLen) + "…(已截断，原长" + (thinking.length()) + "字)";
                }
                AgentLogger.logThinking(thinking);
            }

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

        // 扩展上下文（由各 Collector 采集，排除 user_prompt 避免重复）
        if (!ctx.getExtras().isEmpty()) {
            sb.append("[Extra Context]\n");
            ctx.getExtras().forEach((key, value) -> {
                if (!"user_prompt".equals(key)) {
                    sb.append("- ").append(key).append(": ").append(value).append("\n");
                }
            });
        }

        // 思考模式指令（根据触发器类型决定）
        if (isThinkingEnabled(ctx.getTriggerSource())) {
            int maxLen = TalkingWeaponConfig.THINKING_MAX_LENGTH.get();
            sb.append("\n[Thinking Mode]\n");
            sb.append("请先在 <think> 标签中写下你的思考过程（分析当前情境、玩家状态等），");
            sb.append("然后在标签外写下你的正式回复。\n");
            if (maxLen > 0) {
                sb.append("注意：思考内容请控制在 ").append(maxLen).append(" 字以内，保持简洁。\n");
            }
            sb.append("格式示例：\n");
            sb.append("<think>这里是你的内心思考，对玩家不可见...</think>\n");
            sb.append("这里是你对玩家说的话。\n");
        }

        return sb.toString();
    }

    /**
     * 根据触发源判断是否启用思考模式。
     */
    private static boolean isThinkingEnabled(String triggerSource) {
        if ("right_click".equals(triggerSource)) {
            return TalkingWeaponConfig.RC_ENABLE_THINKING.get();
        } else if ("biome_change".equals(triggerSource)) {
            return TalkingWeaponConfig.BC_ENABLE_THINKING.get();
        }
        return false;
    }
}
