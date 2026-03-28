package dreamdust.talking_weapon.basic_talking_weapon.agent.action.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.action.ActionRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.action.SpeechAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.action.response.AgentActionResponse;
import dreamdust.talking_weapon.basic_talking_weapon.agent.action.response.AgentActionResponseParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AttentionFilter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.ContextSupplementaryFormatter;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.PersonaMatcher;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.SubconsciousBooster;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.VisibleContextSelector;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMChatClient;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMAgentService;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMMessage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMRecallEngine;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentStateStorage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeRecentInteractionsFile;
import dreamdust.talking_weapon.basic_talking_weapon.studio.StudioRuntimeRecallTraceFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** 存放本次构建 System Prompt 时最终经过注意力过滤暴露给模型看的上下文，供动作结算追踪使用 */
    private List<TaggedContextEntry> visibleContexts = new ArrayList<>();

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
            LLMChatClient client = LLMAgentService.buildClient();

            // 触发器的事件指令（归因到触发源），应作为 system prompt 的 [Event] 段落注入，
            // 避免被模型误判为“玩家说的话”。
            String userMessage = firstNonBlank(
                    ctx.getExtra("user_message"),
                    "[OBSERVATION]");
            String eventInstruction = firstNonBlank(
                    ctx.getExtra("event_instruction"),
                    ctx.getExtra("user_prompt"),
                    "请根据当前状态，以武器灵魂的身份发表一到两句话的简短评论。");
            String screenshotBase64 = ctx.getScreenshotBase64();

            // user message 只承载本轮简短观测文本；事件约束统一进入 system prompt 的
            // [Event Instructions] 段，避免把任务规则塞进 user role。
            String userText = userMessage;

            List<LLMMessage> messages = new ArrayList<>();
            messages.add(LLMMessage.system(systemPrompt));
            if (screenshotBase64 != null) {
                messages.add(LLMMessage.userWithImage(userText, screenshotBase64, "image/png"));
            } else {
                messages.add(LLMMessage.user(userText));
            }

            if (isCancelled())
                return;

            // ── 日志：提示词 ──
            AgentLogger.logPrompts(systemPrompt, userText, eventInstruction, screenshotBase64 != null);

            // 4. 调用 LLM
            String rawReply = client.generate(messages).trim();
            AgentLogger.logRawResponse(rawReply);

            if (isCancelled())
                return;

            // 5. 解析 JSON 响应
            AgentActionResponse agentResponse = AgentActionResponseParser.parse(rawReply);
            AgentLogger.logParsedResponse(agentResponse);

            // 6. 处理情绪更新（如果 schema 包含 EMOTION_UPDATE）
            boolean runtimeStateChanged = false;
            if (outputSchema.contains(LLMOutputSchema.EMOTION_UPDATE) && agentResponse.hasEmotionUpdate()) {
                AgentActionResponse.EmotionUpdate eu = agentResponse.getEmotionUpdate();
                state.setPrimaryEmotionByName(eu.emotion);
                state.setEmotionIntensity(eu.intensity);
                AgentLogger.logAction("emotion_update", eu.toString());
                runtimeStateChanged = true;
            }

            // ── 日志：推理草稿 ──
            if (agentResponse.hasThinking()) {
                AgentLogger.logThinking("[thinking]\n" + agentResponse.getThinking());
            }

            // 7 & 8. 执行 actions 并存入 STM 记忆池
            STMPool stmPool = AgentRuntime.getInstance().getMemory().getSTMPool();
            String triggerSrc = ctx.getTriggerSource() != null ? ctx.getTriggerSource() : "unknown";
            List<AgentOutputRecord> records = new java.util.ArrayList<>();
            java.util.LinkedHashSet<String> actionTypes = new java.util.LinkedHashSet<>();

            boolean spoke = false;
            if (agentResponse.hasActions()) {
                for (AgentActionResponse.ActionRequest req : agentResponse.getActions()) {
                    if (req.type == null)
                        continue;
                    String t = req.type.toLowerCase();
                    actionTypes.add(t);

                    if ("speech".equals(t)) {
                        if (req.content != null) {
                            new SpeechAction("剑：" + req.content).perform();
                            AgentLogger.logAction("speech", req.content);
                            records.add(new AgentOutputRecord(AgentOutputRecord.Type.SPEECH, req.content));
                            spoke = true;
                        }
                    } else if ("inner_monologue".equals(t)) {
                        if (req.content != null) {
                            AgentLogger.logThinking("[inner_monologue]\n" + req.content);
                            records.add(new AgentOutputRecord(AgentOutputRecord.Type.INNER_MONOLOGUE, req.content));
                        }
                    } else if ("favorability_update".equals(t)) {
                        if (req.change_amount != null && req.change_amount != 0) {
                            int appliedChange = stmPool.requestFavorabilityChange(req.change_amount,
                                    this.visibleContexts);
                            if (appliedChange != 0) {
                                persona.adjustFavorability(appliedChange);
                                AgentLogger.logAction("favorability_update",
                                        String.format("Request: %d, Applied: %d, Total: %d", req.change_amount,
                                                appliedChange, persona.getFavorability()));
                                runtimeStateChanged = true;
                            } else {
                                AgentLogger.logDebug("好感度变动被限界拦截或截断：" + req.change_amount);
                            }
                        }
                    } else {
                        AgentLogger.logAction(t,
                                req.content != null ? req.content
                                        : (req.change_amount != null ? String.valueOf(req.change_amount)
                                                : "unknown parameter"));
                        // 未来扩展其他 action 会在此做分支，暂只记录日志
                    }
                }
            }

            if (!spoke) {
                AgentLogger.logAction("speech", "(沉默)");
            }

            InteractionTurn turn = new InteractionTurn(
                    triggerSrc,
                    eventInstruction,
                    screenshotBase64,
                    records,
                    new java.util.ArrayList<>(actionTypes),
                    new java.util.ArrayList<>(this.visibleContexts));
            stmPool.pushInteractionTurn(turn);
            StudioRuntimeRecentInteractionsFile.recordTurn(turn);

            if (runtimeStateChanged) {
                AgentStateStorage.requestSaveRuntimeState(state, persona);
            }

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

        // Agent 运行时状态
        sb.append(state.toPromptSnippet()).append("\n");

        List<TaggedContextEntry> rawEntries = ctx.getTaggedEntries();

        // ── STM 写入：将所有采集到的上下文条目（原始未加成）推入短期记忆池 ──
        if (!rawEntries.isEmpty()) {
            AgentRuntime.getInstance().getMemory().getSTMPool().pushContextAll(rawEntries);
        }

        // 事件指令与当前任务（归因到触发源，而不是玩家发言）
        String eventInstruction = firstNonBlank(
                ctx.getExtra("event_instruction"),
                ctx.getExtra("user_prompt"));
        if (eventInstruction != null && !eventInstruction.isEmpty()) {
            sb.append("[Event Instructions]\n").append(eventInstruction).append("\n\n");
        }

        // ── 近期交互回合（STM） ──
        // 将近期回合以“事件 -> 行动”的形式注入 system prompt，让 Agent 对眼前近况保持连续性。
        // 注意：这里不会注入任何图片 Base64，只会标记 [+image]，避免 prompt 爆炸。
        java.util.List<InteractionTurn> recentTurns = memory.getSTMPool().turnSnapshot();
        if (!recentTurns.isEmpty()) {
            sb.append("[Recent Interaction Turns]\n");
            int maxTurns = 12;
            int startIdx = Math.max(0, recentTurns.size() - maxTurns);
            for (int i = startIdx; i < recentTurns.size(); i++) {
                InteractionTurn t = recentTurns.get(i);
                String src = t.getTriggerSource() != null ? t.getTriggerSource() : "unknown";
                boolean chat = "player_chat".equals(src);

                String p = t.getUserPrompt();
                if (p != null && p.length() > 180) {
                    p = p.substring(0, 180) + "...";
                }

                sb.append("- (").append(chat ? "PLAYER_CHAT" : "EVENT").append(") ")
                        .append(src);
                if (p != null && !p.isBlank()) {
                    sb.append(" | instruction: ").append(p.replace("\n", " "));
                }
                if (t.hasImage()) {
                    sb.append(" [+image]");
                }
                sb.append("\n");

                for (AgentOutputRecord rec : t.getAgentActions()) {
                    String text = rec.getText();
                    if (text != null && text.length() > 200) {
                        text = text.substring(0, 200) + "...";
                    }
                    sb.append("    -> ").append(rec.getType()).append(": ")
                            .append(text != null ? text.replace("\n", " ") : "").append("\n");
                }
            }
            sb.append("\n");
        }

        // 标签化上下文（结构化信息，经过认知管线 v4 处理后注入）

        // ── 日志：打印所有采集到的 extras 供调试分析 ──
        if (!ctx.getExtras().isEmpty()) {
            StringBuilder extrasLog = new StringBuilder("[Extras Dump]\n");
            for (java.util.Map.Entry<String, String> e : ctx.getExtras().entrySet()) {
                extrasLog.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            }
            AgentLogger.logDebug(extrasLog.toString());

            // 仅将简短的概述注入提示词（如存在）
            if (ContextSupplementaryFormatter.appendSection(
                    sb,
                    "[Supplementary Info]",
                    "- ",
                    ctx.getExtras())) {
                sb.append("\n");
            }
        }

        if (!rawEntries.isEmpty()) {
            LocationRegistry locationReg = LocationRegistry.getInstance();

            // ── Phase 1: 潜意识直通 (0~2 条绕过 Top-K) ──
            VisibleContextSelector.Selection visibleSelection = VisibleContextSelector.select(
                    rawEntries,
                    ctx.getTriggerSource(),
                    state.getConcentration());
            List<TaggedContextEntry> subconsciousPicks = visibleSelection.subconsciousPicks();
            if (!subconsciousPicks.isEmpty()) {
                StringBuilder scLog = new StringBuilder("[潜意识直通] 抽取了 " + subconsciousPicks.size() + " 条直通条目:\n");
                for (TaggedContextEntry sc : subconsciousPicks) {
                    scLog.append("  → ").append(sc.getDescription()).append(" (importance=").append(String.format("%.2f", sc.getBaseImportance())).append(")\n");
                }
                AgentLogger.logDebug(scLog.toString());
            } else {
                AgentLogger.logDebug("[潜意识直通] 本轮无直通条目");
            }

            // ── Phase 2: Top-K 截断 (在原始未扰动的列表上执行) ──
            List<TaggedContextEntry> mergedVisible = visibleSelection.visibleContexts();

            // 记录最终可见层，供动作结算追踪使用
            this.visibleContexts.clear();
            this.visibleContexts.addAll(mergedVisible);

            // ── Phase 4: 偏好确定性全量匹配 (PersonaMatcher) ──
            PersonaMatcher.MatchResult personaResult = PersonaMatcher.match(mergedVisible, persona);
            Set<String> triggeringDetailTags = personaResult.triggeringDetailTags;

            if (!personaResult.activeTraits.isEmpty()) {
                StringBuilder pmLog = new StringBuilder("[偏好匹配] 激发了 " + personaResult.activeTraits.size() + " 条偏好:\n");
                for (var trait : personaResult.activeTraits) {
                    pmLog.append("  ★ ").append(trait.getDescription()).append("\n");
                }
                for (var trigger : personaResult.triggeredTraits) {
                    pmLog.append("    <- context: ").append(trigger.contextEntry.getDescription()).append("\n");
                    pmLog.append("       persona: ").append(trigger.trait.getDescription()).append("\n");
                    pmLog.append("       match_mode: ").append(trigger.matchMode).append("\n");
                    if (!trigger.matchedContextTags.isEmpty()) {
                        pmLog.append("       context_tags: ").append(trigger.matchedContextTags).append("\n");
                    }
                    if (!trigger.matchedTraitTags.isEmpty()) {
                        pmLog.append("       trait_tags: ").append(trigger.matchedTraitTags).append("\n");
                    }
                }
                if (!triggeringDetailTags.isEmpty()) {
                    pmLog.append("  触发标签: ").append(triggeringDetailTags).append("\n");
                }
                AgentLogger.logDebug(pmLog.toString());
            }

            // ── Phase 5: 长期记忆联想召回 ──
            LTMRecallEngine.RecallResult recallResult = LTMRecallEngine.recall(mergedVisible, memory, state, ctx.getGameTime());
            if (!recallResult.traceLines.isEmpty()) {
                StringBuilder recallLog = new StringBuilder("[记忆召回]\n");
                for (String line : recallResult.traceLines) {
                    recallLog.append("  ").append(line).append("\n");
                }
                if (!recallResult.triggerTags.isEmpty()) {
                    recallLog.append("  触发标签: ").append(recallResult.triggerTags).append("\n");
                }
                AgentLogger.logDebug(recallLog.toString());
            }
            StudioRuntimeRecallTraceFile.writeLatest(ctx, eventInstruction, mergedVisible, state, recallResult);

            // Agent 人格画像（直接传入筛选好的词条）
            String personaSnippet = persona.toPromptSnippet(personaResult.activeTraits);
            if (!personaSnippet.isBlank()) {
                sb.append(personaSnippet).append("\n");
            }

            TagDescriptionDictionary tagDict = TagDescriptionDictionary.getInstance();

            if (!recallResult.promptMemories.isEmpty()) {
                sb.append("[Recalled Long-Term Memories]\n");
                for (LTMEntry ltm : recallResult.promptMemories) {
                    sb.append("- ").append(ltm.getDescription());
                    String timeSemantic = describePromptMemoryTimeSemantic(ltm, tagDict);
                    if (!timeSemantic.isBlank()) {
                        sb.append(" (时间语义: ").append(timeSemantic).append(")");
                    }
                    String environmentAnchor = describePromptEnvironmentAnchor(ltm);
                    if (!environmentAnchor.isBlank()) {
                        sb.append(" [").append(environmentAnchor).append("]");
                    }
                    List<String> cues = describePromptVisibleDetails(ltm.getDetailTags(), tagDict, locationReg);
                    if (!cues.isEmpty()) {
                        sb.append(" (线索: ").append(String.join("; ", cues)).append(")");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            List<LTMEntry> associationSandboxMemories = resolveAssociationSandboxMemories(memory, recallResult);
            if (!associationSandboxMemories.isEmpty()) {
                sb.append("[Associative Echoes]\n");
                for (LTMEntry ltm : associationSandboxMemories) {
                    sb.append("- ").append(ltm.getDescription());
                    String timeSemantic = describePromptMemoryTimeSemantic(ltm, tagDict);
                    if (!timeSemantic.isBlank()) {
                        sb.append(" (时间语义: ").append(timeSemantic).append(")");
                    }
                    List<String> cues = describePromptVisibleDetails(ltm.getDetailTags(), tagDict, locationReg);
                    if (!cues.isEmpty()) {
                        sb.append(" (联想线索: ").append(String.join("; ", cues)).append(")");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("[Tagged Context]\n");
            for (TaggedContextEntry entry : mergedVisible) {
                float finalScore = AttentionFilter.calculateFinalScore(entry, state.getConcentration());

                sb.append("- [").append(entry.getFocusTags()).append("] ");
                sb.append(entry.getDescription());
                sb.append(String.format(" (客观重要度: %.2f -> 主观得分: %.2f)", entry.getBaseImportance(), finalScore));

                if (!entry.getDetailTags().isEmpty()) {
                    List<String> translated = describePromptVisibleDetails(entry.getDetailTags(), tagDict, locationReg);
                    if (!translated.isEmpty()) {
                        sb.append(" (").append(String.join("; ", translated)).append(")");
                    }
                }
                sb.append("\n");
            }
        }

        // 动态接入 FocusTag 和 Emotion 类型描述段落（按触发器声明的 Schema 按需注入）
        if (outputSchema.contains(LLMOutputSchema.FOCUS_TAG_GUIDE)) {
            sb.append("\n").append(PromptRegistry.getInstance().getFocusTagDescription()).append("\n");
        }
        if (outputSchema.contains(LLMOutputSchema.EMOTION_TYPE_GUIDE)) {
            sb.append("\n").append(PromptRegistry.getInstance().getEmotionTypeDescription()).append("\n");
        }

        // 动态 JSON 输出格式指令（根据 outputSchema 拼装）
        sb.append(buildOutputFormatInstruction(outputSchema, ctx));

        return sb.toString();
    }

    private static List<String> describePromptVisibleDetails(Collection<DetailTag> detailTags,
            TagDescriptionDictionary tagDict, LocationRegistry locationReg) {
        List<String> translated = new ArrayList<>();
        if (detailTags == null) {
            return translated;
        }
        for (DetailTag dt : detailTags) {
            if (!TagPolicy.isPromptVisible(dt)) {
                continue;
            }
            String raw = dt.toString();
            if (dt.getPrefix().equals(DetailTag.CUSTOM_SPECIFIC) && locationReg.contains(dt.getValue())) {
                String locName = dt.getValue();
                String locDesc = locationReg.describeLocation(locName);
                translated.add(locDesc != null ? locName + ": " + locDesc : raw);
            } else {
                String desc = tagDict.describe(raw);
                translated.add(desc != null ? desc : raw);
            }
        }
        return translated;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String describePromptMemoryTimeSemantic(LTMEntry ltm, TagDescriptionDictionary tagDict) {
        if (ltm == null) {
            return "";
        }
        DetailTag semanticTimeTag = ltm.getSemanticTimeTag();
        if (semanticTimeTag != null && TagPolicy.isPromptVisible(semanticTimeTag)) {
            String desc = tagDict.describeOrRaw(semanticTimeTag.toString());
            if (desc != null && !desc.isBlank()) {
                return desc;
            }
        }
        String semanticTimeLabel = ltm.getSemanticTimeLabel();
        return semanticTimeLabel != null ? semanticTimeLabel : "";
    }

    private static String describePromptEnvironmentAnchor(LTMEntry ltm) {
        if (ltm == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (Boolean.TRUE.equals(ltm.getRaining())) {
            parts.add("雨中");
        }
        if (ltm.getDimensionId() != null && !ltm.getDimensionId().isBlank()) {
            parts.add("维度:" + ltm.getDimensionId());
        }
        if (ltm.getBiomeId() != null && !ltm.getBiomeId().isBlank()
                && !"unknown".equalsIgnoreCase(ltm.getBiomeId())) {
            parts.add("群系:" + ltm.getBiomeId());
        }
        return String.join(" / ", parts);
    }

    private static List<LTMEntry> resolveAssociationSandboxMemories(AgentMemory memory,
            LTMRecallEngine.RecallResult recallResult) {
        if (memory == null || recallResult == null || recallResult.runtimeState == null
                || recallResult.runtimeState.associationSandbox == null) {
            return List.of();
        }

        List<String> sandboxMemoryIds = recallResult.runtimeState.associationSandbox.memoryIds;
        if (sandboxMemoryIds == null || sandboxMemoryIds.isEmpty()) {
            return List.of();
        }

        Set<String> sourceMemoryIds = new LinkedHashSet<>();
        if (recallResult.runtimeState.associationSandbox.sourceMemoryIds != null) {
            sourceMemoryIds.addAll(recallResult.runtimeState.associationSandbox.sourceMemoryIds);
        }

        LinkedHashMap<String, LTMEntry> memoryById = new LinkedHashMap<>();
        for (LTMEntry entry : memory.getLtmRecords()) {
            if (entry != null && entry.getId() != null) {
                memoryById.put(entry.getId().toString(), entry);
            }
        }

        List<LTMEntry> result = new ArrayList<>();
        for (String memoryId : sandboxMemoryIds) {
            if (memoryId == null || memoryId.isBlank() || sourceMemoryIds.contains(memoryId)) {
                continue;
            }
            LTMEntry entry = memoryById.get(memoryId);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 根据 outputSchema 和允许的动作列表动态拼装 JSON 输出格式指令。
     * 仅将 schema 中声明的字段列入 JSON 属性，其余字段不出现在指令中。
     */
    private static String buildOutputFormatInstruction(EnumSet<LLMOutputSchema> schema, AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[Output Format]\n");
        sb.append("你必须严格以下面的 JSON 格式回复，JSON 之外不得有任何多余文字：\n");
        sb.append("{\n");

        boolean first = true;
        if (schema.contains(LLMOutputSchema.THINKING)) {
            sb.append("  \"thinking\": \"(推理草稿：分析当前情境，决定要做什么——仅作草稿，不记入记忆；可省略)\"");
            first = false;
        }
        if (schema.contains(LLMOutputSchema.ACTIONS)) {
            if (!first)
                sb.append(",\n");
            sb.append("  \"actions\": [\n");

            String enabledStr = ctx.getExtra("enabled_actions");
            java.util.Set<String> allowedSet = new java.util.HashSet<>();
            if (enabledStr != null && !enabledStr.isBlank()) {
                for (String s : enabledStr.split(","))
                    allowedSet.add(s.trim().toLowerCase());
            } else {
                allowedSet.add("speech");
                allowedSet.add("inner_monologue");
            }

            java.util.List<ActionRegistry.LLMActionType> activeActions = new java.util.ArrayList<>();
            for (ActionRegistry.LLMActionType act : ActionRegistry.getRegisteredActions()) {
                if (allowedSet.contains(act.getName().toLowerCase())) {
                    activeActions.add(act);
                }
            }

            for (int i = 0; i < activeActions.size(); i++) {
                sb.append("    ").append(activeActions.get(i).getFormatInstruction());
                if (i < activeActions.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }

            sb.append("  ]");
            first = false;
        }
        if (schema.contains(LLMOutputSchema.EMOTION_UPDATE)) {
            if (!first)
                sb.append(",\n");
            sb.append("  \"emotion_update\": { ");
            sb.append("\"emotion\": \"(其中一种: FEARFUL/HAPPY/ANGRY/EXCITED/BORED/SAD/CURIOUS/ANXIOUS/NEUTRAL)\",");
            sb.append(" \"intensity\": 0-100的整数 ");
            sb.append("}");
            first = false;
        }
        sb.append("\n}\n");
        return sb.toString();
    }

}
