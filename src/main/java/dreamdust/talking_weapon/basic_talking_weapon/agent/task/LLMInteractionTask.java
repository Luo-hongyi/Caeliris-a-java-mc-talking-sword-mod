package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.action.SpeechAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AttentionFilter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponse;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponseParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMAgentService;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMsg;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.TraitEntry;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 从触发器的 trigger_source 属性读取 OutputSchema。
     * 如果 Context extras 中包含 trigger 引用，使用其 schema；否则使用默认 schema。
     * 注意：目前 trigger 引用通过 context extras 传递 schema。
     */
    private EnumSet<LLMOutputSchema> resolveOutputSchema() {
        // Context extras 中可以包含 "output_schema" key，存储逗号分隔的 schema 名称
        String schemaStr = getContext().getExtras().get("output_schema");
        if (schemaStr != null && !schemaStr.isBlank()) {
            EnumSet<LLMOutputSchema> schema = EnumSet.noneOf(LLMOutputSchema.class);
            for (String name : schemaStr.split(",")) {
                try {
                    schema.add(LLMOutputSchema.valueOf(name.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (!schema.isEmpty())
                return schema;
        }
        return LLMOutputSchema.defaultSchema();
    }

    @Override
    public void execute() {
        if (isCancelled())
            return;

        AgentRuntime runtime = AgentRuntime.getInstance();
        AgentMemory memory = runtime.getMemory();
        AgentState state = runtime.getState();
        AgentPersona persona = runtime.getPersona();
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

            // 1.5. 从配置读取角色背景与性格，组合后作为 Identity 写入记忆
            String bg = PromptRegistry.getInstance().getRoleBackground();
            String per = PromptRegistry.getInstance().getRolePersonality();
            StringBuilder idBuilder = new StringBuilder();
            if (bg != null && !bg.isBlank())
                idBuilder.append("[Role Background]\n").append(bg).append("\n\n");
            if (per != null && !per.isBlank())
                idBuilder.append("[Role Personality]\n").append(per).append("\n");
            memory.setIdentity(idBuilder.toString().trim());

            // 中间层：读取 outputSchema
            EnumSet<LLMOutputSchema> outputSchema = resolveOutputSchema();

            // 2. 构建 system prompt
            String systemPrompt = buildSystemPrompt(memory, state, persona, ctx, outputSchema);

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

            // 6. 处理情绪更新（如果 schema 包含 EMOTION_UPDATE）
            if (outputSchema.contains(LLMOutputSchema.EMOTION_UPDATE) && agentResponse.hasEmotionUpdate()) {
                AgentResponse.EmotionUpdate eu = agentResponse.getEmotionUpdate();
                state.setPrimaryEmotionByName(eu.emotion);
                state.setEmotionIntensity(eu.intensity);
                AgentLogger.logAction("emotion_update", eu.toString());
            }

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
            // 修正：只有真实玩家对话（如 triggerSource 为 player_chat）才使其作为 user message
            // 计入短期记忆，以防各类触发器的系统提示词污染记忆
            if ("player_chat".equals(ctx.getTriggerSource())) {
                memory.addShortTerm(AgentMsg.userWithImage(userPrompt, screenshotBase64));
            }
            memory.addShortTerm(AgentMsg.assistant(speechForMemory));

            // ── 日志结束 ──
            AgentLogger.endSession();

        } catch (Throwable e) {
            if (!isCancelled()) {
                System.err.println(
                        "[TalkingWeapon] LLMInteractionTask failed: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
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
     * 组装 system prompt：包含身份、记忆、状态、Persona、上下文以及动态 JSON 输出指令。
     */
    private String buildSystemPrompt(AgentMemory memory, AgentState state,
            AgentPersona persona, AgentContext ctx,
            EnumSet<LLMOutputSchema> outputSchema) {
        StringBuilder sb = new StringBuilder();

        // 身份
        String identity = memory.getIdentity();
        if (identity != null && !identity.isEmpty()) {
            sb.append(identity).append("\n\n");
        }

        // 认知规则
        sb.append(PromptRegistry.getInstance().getSystemCognitionRule()).append("\n\n");

        // 长期记忆摘要
        if (!memory.getLongTerm().isEmpty()) {
            sb.append("[Long-term Memory]\n");
            for (String entry : memory.getLongTerm()) {
                sb.append("- ").append(entry).append("\n");
            }
            sb.append("\n");
        }

        // Agent 运行时状态
        sb.append(state.toPromptSnippet()).append("\n");

        // 收集当前帧所有活跃的 FocusTag 和 DetailTag，供 Persona 词条与隐式记忆筛选使用
        Set<FocusTag> activeFocusTags = new HashSet<>();
        Set<String> activeDetailTags = new HashSet<>();
        TagDescriptionDictionary tagDict = TagDescriptionDictionary.getInstance();

        for (TaggedContextEntry entry : ctx.getTaggedEntries()) {
            activeFocusTags.addAll(entry.getFocusTags());
            for (DetailTag dt : entry.getDetailTags()) {
                String rawTag = dt.toString();
                activeDetailTags.add(rawTag);
                // 展开预设的额外环境语义（Extras）供记忆联想匹配
                activeDetailTags.addAll(tagDict.getExtras(rawTag));
            }
        }

        // 找到哪些 DetailTag 成功触发了性格词条联想
        Set<String> triggeringDetailTags = new HashSet<>();
        List<TraitEntry> activeTraits = new ArrayList<>();
        for (TraitEntry t : persona.getTraits()) {
            boolean triggered = false;
            // 1. 通过 FocusTag 触发
            if (t.getAssociatedTag() != null && activeFocusTags.contains(t.getAssociatedTag())) {
                triggered = true;
            }
            // 2. 通过 DetailTag 联想触发
            for (String dt : t.getDetailTags()) {
                if (activeDetailTags.contains(dt)) {
                    triggered = true;
                    triggeringDetailTags.add(dt);
                }
            }
            if (triggered) {
                activeTraits.add(t);
            }
        }

        // Agent 人格画像（直接传入筛选好的词条）
        String personaSnippet = persona.toPromptSnippet(activeTraits);
        if (!personaSnippet.isBlank()) {
            sb.append(personaSnippet).append("\n");
        }

        // 明确指令与当前任务
        String userPrompt = ctx.getExtra("user_prompt");
        if (userPrompt != null && !userPrompt.isEmpty()) {
            sb.append("[User Instructions]\n").append(userPrompt).append("\n\n");
        }

        // 标签化上下文（结构化信息，经过注意力过滤后注入）
        List<TaggedContextEntry> rawEntries = ctx.getTaggedEntries();
        if (!rawEntries.isEmpty()) {
            LocationRegistry locationReg = LocationRegistry.getInstance();
            
            int topK = TalkingWeaponConfig.TOP_K_RIGHT_CLICK.get();
            String source = ctx.getTriggerSource();
            if ("biome_change".equals(source)) {
                topK = TalkingWeaponConfig.TOP_K_BIOME_CHANGE.get();
            } else if ("player_chat".equals(source)) {
                topK = TalkingWeaponConfig.TOP_K_CHAT.get();
            }
            
            List<TaggedContextEntry> filteredEntries = AttentionFilter.filterAndTopN(rawEntries, topK, 0.2f, state.getConcentration());

            sb.append("[Tagged Context]\n");
            for (TaggedContextEntry entry : filteredEntries) {
                // 如果是隐式标签，且它的 detailTags 没有一个命中 triggeringDetailTags，则完全跳过
                if (entry.isAssociativeOnly()) {
                    boolean triggered = false;
                    for (DetailTag dt : entry.getDetailTags()) {
                        if (triggeringDetailTags.contains(dt.toString())) {
                            triggered = true;
                            break;
                        }
                    }
                    if (!triggered) {
                        continue;
                    }
                }

                float finalScore = AttentionFilter.calculateFinalScore(entry, state.getConcentration());

                sb.append("- [").append(entry.getFocusTags()).append("] ");
                sb.append(entry.getDescription());
                sb.append(String.format(" (客观重要度: %.2f -> 主观得分: %.2f)", entry.getBaseImportance(), finalScore));

                if (!entry.getDetailTags().isEmpty()) {
                    java.util.List<String> translated = new java.util.ArrayList<>();
                    for (DetailTag dt : entry.getDetailTags()) {
                        String raw = dt.toString();
                        if (raw.startsWith("loc:")) {
                            String locName = raw.substring(4);
                            String locDesc = locationReg.describeLocation(locName);
                            translated.add(locDesc != null ? locName + ": " + locDesc : raw);
                        } else {
                            String desc = tagDict.describe(raw);
                            translated.add(desc != null ? desc : raw);

                            // 若存在衍生的 Extras 预设标签，也尝试输出其自然语言描述
                            List<String> extras = tagDict.getExtras(raw);
                            if (!extras.isEmpty()) {
                                List<String> extraDescs = tagDict.describeAll(extras);
                                if (!extraDescs.isEmpty()) {
                                    translated.add("附带特征: " + String.join(", ", extraDescs));
                                }
                            }
                        }
                    }
                    sb.append(" (").append(String.join("; ", translated)).append(")");
                }
                sb.append("\n");
            }
        }

        // 动态接入 FocusTag 和 Emotion 类型描述段落（可插拔）
        sb.append("\n").append(PromptRegistry.getInstance().getFocusTagDescription()).append("\n");
        sb.append("\n").append(PromptRegistry.getInstance().getEmotionTypeDescription()).append("\n");

        // 动态 JSON 输出格式指令（根据 outputSchema 拼装）
        sb.append(buildOutputFormatInstruction(outputSchema));

        return sb.toString();
    }

    /**
     * 根据 outputSchema 动态拼装 JSON 输出格式指令。
     * 仅将 schema 中声明的字段列入 JSON 属性，其余字段不出现在指令中。
     */
    private static String buildOutputFormatInstruction(EnumSet<LLMOutputSchema> schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[Output Format]\n");
        sb.append("你必须严格以下面的 JSON 格式回复，JSON 之外不得有任何多余文字：\n");
        sb.append("{\n");

        boolean first = true;
        if (schema.contains(LLMOutputSchema.THINKING)) {
            sb.append("  \"thinking\": \"(推理草稿：分析当前情境，决定说什么、想什么——仅作草稿，不记入记忆；可省略)\"");
            first = false;
        }
        if (schema.contains(LLMOutputSchema.INNER_MONOLOGUE)) {
            if (!first)
                sb.append(",\n");
            sb.append("  \"inner_monologue\": \"(剑灵的内心独白，对持有者不可见；可省略)\"");
            first = false;
        }
        if (schema.contains(LLMOutputSchema.SPEECH)) {
            if (!first)
                sb.append(",\n");
            sb.append("  \"speech\": \"(对持有者说出口的话；若沉默则省略或设为空字符串)\"");
            first = false;
        }
        if (schema.contains(LLMOutputSchema.EMOTION_UPDATE)) {
            if (!first)
                sb.append(",\n");
            sb.append("  \"emotion_update\": { ");
            sb.append("\"emotion\": \"(其中一种: FEARFUL/HAPPY/ANGRY/EXCITED/BORED/SAD/CURIOUS/ANXIOUS/NEUTRAL)\",");
            sb.append(" \"intensity\": 0-100的整数 ");
            sb.append("}");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    }
