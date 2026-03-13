package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.action.SpeechAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponse;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponseParser;
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
 * 需要调用 LLM 的交互任务（前台队列）。
 * <p>
 * 完整流程：构建 prompt → 调用 LLM → 解析 JSON 响应 → SpeechAction 展示 → 更新记忆。
 * <p>
 * LLM 输出格式为 JSON（三字段）：
 *
 * <pre>
 * {
 *   "thinking":        "推理草稿，LLM 草稿纸，不进记忆",
 *   "inner_monologue": "剑灵的内心独白，对持有者不可见",
 *   "speech":          "对持有者说出口的话"
 * }
 * </pre>
 */
public class LLMInteractionTask extends AgentTask {

    /** 前台交互任务默认优先级 */
    public static final int DEFAULT_PRIORITY = 10;

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

            // 2. 构建 system prompt
            String systemPrompt = buildSystemPrompt(memory, state, ctx);

            // ── 日志：状态与记忆 ──
            AgentLogger.logAgentState(memory, state);

            // 3. 构建 LLM 请求
            ChatLanguageModel model = LLMAgentService.buildModel();

            String userPrompt = ctx.getExtras().getOrDefault("user_prompt",
                    "请根据当前状态，以武器灵魂的身份发表一到两句话的简短评论。");
            String screenshotBase64 = ctx.getScreenshotBase64();

            UserMessage message;
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

            // 5. 解析 JSON 响应
            AgentResponse agentResponse = AgentResponseParser.parse(rawReply);
            AgentLogger.logParsedResponse(agentResponse);

            // 6. （占位：情绪变化、持有者画像等扩展字段将在后续迭代加入）

            // 7. 执行 SpeechAction（只有 speech 字段非空时才说话）
            if (agentResponse.hasSpeech()) {
                new SpeechAction("剑：" + agentResponse.getSpeech()).perform();
            }

            // ── 日志：推理草稿 ──
            if (agentResponse.hasThinking()) {
                AgentLogger.logThinking("[thinking]\n" + agentResponse.getThinking());
            }

            // ── 日志：内心独白 ──
            if (agentResponse.hasInnerMonologue()) {
                AgentLogger.logThinking("[inner_monologue]\n" + agentResponse.getInnerMonologue());
            }

            // ── 日志：Agent 行动 ──
            AgentLogger.logAction("speech", agentResponse.hasSpeech() ? agentResponse.getSpeech() : "(沉默)");

            // 8. 更新记忆
            String speechForMemory = agentResponse.hasSpeech() ? agentResponse.getSpeech() : "(沉默)";
            memory.addShortTerm(AgentMsg.userWithImage(userPrompt, screenshotBase64));
            memory.addShortTerm(AgentMsg.assistant(speechForMemory));

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
     * 组装 system prompt：包含身份、记忆、状态、上下文以及 JSON 输出指令。
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

        // 当前上下文（环境基础信息）
        sb.append("[Context] ").append(ctx.toString()).append("\n");

        // 扩展上下文（各 Collector 采集，排除 user_prompt）
        if (!ctx.getExtras().isEmpty()) {
            sb.append("[Extra Context]\n");
            ctx.getExtras().forEach((key, value) -> {
                if (!"user_prompt".equals(key)) {
                    sb.append("- ").append(key).append(": ").append(value).append("\n");
                }
            });
        }

        // 标签化上下文（结构化信息，经字典翻译后注入）
        java.util.List<TaggedContextEntry> taggedEntries = ctx.getTaggedEntries();
        if (!taggedEntries.isEmpty()) {
            TagDescriptionDictionary tagDict = TagDescriptionDictionary.getInstance();
            LocationRegistry locationReg = LocationRegistry.getInstance();

            sb.append("[Tagged Context]\n");
            for (TaggedContextEntry entry : taggedEntries) {
                sb.append("- [").append(entry.getFocusTags()).append("] ");
                sb.append(entry.getDescription());

                // 将 DetailTag 转换为自然语言標注
                if (!entry.getDetailTags().isEmpty()) {
                    java.util.List<String> translated = new java.util.ArrayList<>();
                    for (DetailTag dt : entry.getDetailTags()) {
                        String raw = dt.toString();
                        if (raw.startsWith("loc:")) {
                            // loc: 标签解析地点坐标
                            String locName = raw.substring(4);
                            String locDesc = locationReg.describeLocation(locName);
                            translated.add(locDesc != null ? locName + ": " + locDesc : raw);
                        } else {
                            // 其他标签查自然语言字典
                            String desc = tagDict.describe(raw);
                            translated.add(desc != null ? desc : raw);
                        }
                    }
                    sb.append(" (").append(String.join(", ", translated)).append(")");
                }
                sb.append("\n");
            }
        }

        // ── JSON 输出格式指令 ──
        sb.append("\n[Output Format]\n");
        sb.append("你必须严格以下面的 JSON 格式回复，JSON 之外不得有任何多余文字：\n");
        sb.append("{\n");
        sb.append("  \"thinking\": \"（推理草稿：分析当前情境，决定说什么、想什么——仅作草稿，不记入记忆；可省略）\",\n");
        sb.append("  \"inner_monologue\": \"（剑灵的内心独白，对持有者不可见；可省略）\",\n");
        sb.append("  \"speech\": \"（对持有者说出口的话；若沉默则省略或设为空字符串）\"\n");
        sb.append("}\n");

        return sb.toString();
    }
}
