package dreamdust.talking_weapon.basic_talking_weapon.agent.task;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.WorldTimeSemantics;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponse;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.AgentResponseParser;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMChatClient;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMAgentService;
import dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service.LLMMessage;
import dreamdust.talking_weapon.basic_talking_weapon.agent.logging.AgentLogger;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentOutputRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.LTMEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentStateStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * 反思任务：后台使用 LLM 对短期记忆进行压缩，生成长期记忆摘要并进行好感度清算。
 * <p>
 * 低优先级任务，在空闲时执行。将短期记忆窗口中的对话压缩为一段语义摘要，
 * 写入长期记忆层，并在完成时针对本纪元进行好感度清算。最终清空短期记忆。
 */
public class ReflectionTask extends AgentTask {

    /** 最低优先级 (数字最大)，在空闲时执行 */
    public static final int DEFAULT_PRIORITY = 50;

    public ReflectionTask(AgentContext context) {
        super(DEFAULT_PRIORITY, context);
    }

    @Override
    public void execute() {
        if (isCancelled()) return;

        AgentRuntime runtime = AgentRuntime.getInstance();
        AgentMemory memory = runtime.getMemory();
        AgentState state = runtime.getState();
        AgentPersona persona = runtime.getPersona();
        AgentContext ctx = getContext();
        boolean runtimeStateChanged = false;

        List<InteractionTurn> turns = memory.getSTMPool().turnSnapshot();
        if (turns.isEmpty()) {
            // 没有值得反思的记忆，直接清空 STMPool 返回
            memory.getSTMPool().clear();
            return;
        }

        AgentLogger.logAction("ReflectionTask", "Beginning epoch settlement and synthesis.");

        // 1. 构建 Prompt
        String systemPrompt = buildSystemPrompt(memory, state, persona, ctx, turns);

        // 2. 调用 LLM
        try {
            LLMChatClient client = LLMAgentService.buildClient();
            List<LLMMessage> messages = new ArrayList<>();
            messages.add(LLMMessage.system(systemPrompt));
            messages.add(LLMMessage.user("开始回忆总结吧。"));

            String responseText = client.generate(messages);
            
            AgentLogger.logAction("ReflectionTask", "LLM Reply: \n" + responseText);

            // 3. 解析响应
            AgentResponse agentResponse = AgentResponseParser.parse(responseText);

            // 4. 处理 LTM 合成
            if (agentResponse.hasLtmSynthesis()) {
                AgentResponse.LtmSynthesis synth = agentResponse.getLtmSynthesis();
                
                LTMEntry.Builder builder = LTMEntry.builder()
                        .sourceType(LTMEntry.SourceType.SYNTHESIZED)
                        .description(synth.description)
                        .importance(synth.importance)
                        .occurredGameTime(ctx.getDayTime())
                        .semanticTimeLabel(WorldTimeSemantics.describeMemoryTimeAnchor(ctx.getDayTime()))
                        .dimensionId(ctx.getExtra("dimension"))
                        .biomeId(ctx.getBiome())
                        .raining(ctx.isRaining());

                if (synth.focus_tags != null) {
                    for (String fStr : synth.focus_tags) {
                        try {
                            builder.focus(FocusTag.valueOf(fStr.toUpperCase()));
                        } catch (Exception ignored) {}
                    }
                }
                if (synth.detail_tags != null) {
                    for (String dStr : synth.detail_tags) {
                        try {
                            builder.detail(TagPolicy.parseRegisteredTag(dStr));
                        } catch (IllegalArgumentException e) {
                            AgentLogger.logDebug("[ReflectionTask] 忽略未登记或非法的 detail tag: " + dStr);
                        }
                    }
                }

                LTMEntry newLtm = builder.build();
                memory.addLTMRecord(newLtm);
                AgentLogger.logAction("ReflectionTask", "Generated LTM: " + newLtm.toString());
            }

            // 5. 处理总结算好感度变动与碎碎念
            if (agentResponse.hasActions()) {
                for (AgentResponse.ActionRequest req : agentResponse.getActions()) {
                    if (req.type != null && "favorability_settlement".equalsIgnoreCase(req.type)) {
                        if (req.change_amount != null && req.change_amount != 0) {
                            // 大清算好感度直接应用到 Persona，不经过疲劳扣留
                            persona.adjustFavorability(req.change_amount);
                            AgentLogger.logAction("favorability_settlement", "Epoch Settled Favorability by " + req.change_amount + ". Total: " + persona.getFavorability());
                            runtimeStateChanged = true;
                        }
                    } else if (req.type != null && "inner_monologue".equalsIgnoreCase(req.type)) {
                        if (req.content != null) {
                            AgentLogger.logThinking("[Reflection Monologue]\n" + req.content);
                        }
                    }
                }
            }

        } catch (Exception e) {
            AgentLogger.logError("ReflectionTask 执行异常", e);
        } finally {
            if (runtimeStateChanged) {
                AgentStateStorage.requestSaveRuntimeState(state, persona);
            }
            // 6. 清理：无论成功失败，推进纪元
            memory.getSTMPool().clear();
            AgentLogger.logAction("ReflectionTask", "Epoch cleared. New memory epoch begins.");
        }
    }

    private String buildSystemPrompt(AgentMemory memory, AgentState state, AgentPersona persona, AgentContext ctx, List<InteractionTurn> turns) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(memory.getIdentity()).append("\n\n");
        sb.append("[System Persona]\n");
        sb.append(persona.toPromptSnippet(persona.getPersonaEntries())).append("\n\n");
        
        sb.append("[Reflection Instructions]\n");
        sb.append("你现在的任务是开始“长梦反思”。你需要仔细审视过去这段时间（此纪元）内你们之间发生的所有互动，用你的主观视角提取并合成一份记忆切片。\n");
        
        if (state.getCurrentDesireTag() != null) {
            String desc = state.getDesireDescription();
            sb.append("请特别反思，主人的这一系列行为，是否满足了你的潜意识渴望: 【").append(desc != null ? desc : "未知").append("】？\n");
        }
        
        sb.append("因为是纪元大清算，你需要通过 favorability_settlement 动作给予总结性的好感度赏罚。\n");
        sb.append("在此过程中，你可以通过 inner_monologue 来倾吐评价或者抱怨。\n\n");

        sb.append("[Memories to Synthesize]\n");
        for (InteractionTurn t : turns) {
            String src = t.getTriggerSource() != null ? t.getTriggerSource() : "unknown";
            boolean chat = "player_chat".equals(src);
            String p = t.getUserPrompt();
            
            sb.append("- (").append(chat ? "PLAYER_CHAT" : "EVENT").append(") ").append(src);
            if (p != null && !p.isBlank()) {
                sb.append(" | input: ").append(p.replace("\n", " "));
            }
            sb.append("\n");

            for (AgentOutputRecord rec : t.getAgentActions()) {
                String text = rec.getText();
                sb.append("    -> ").append(rec.getType()).append(": ")
                  .append(text != null ? text.replace("\n", " ") : "").append("\n");
            }
        }
        sb.append("\n");
        
        sb.append("[Output Format]\n");
        sb.append("你必须严格以下面的 JSON 格式回复，并且只输出合法的 JSON 文本：\n");
        sb.append("{\n");
        sb.append("  \"thinking\": \"(推理草稿：审视经历，决策好感度增减与生成要点)\",\n");
        sb.append("  \"ltm_synthesis\": {\n");
        sb.append("    \"description\": \"(对这段经历的凝练描述，作为你的主观回忆，建议 1-3 句话)\",\n");
        sb.append("    \"focus_tags\": [\"(挑选几个相关的 FocusTag，比如 COMBAT, MOVEMENT)\"],\n");
        sb.append("    \"detail_tags\": [\"(挑选最相关的 DetailTag，例如 native_specific:minecraft:creeper 或 custom_generic:combat_intense，可留空)\"],\n");
        sb.append("    \"importance\": (0.0到1.0的浮点数，代表这事在你心里的震撼和重要程度)\n");
        sb.append("  },\n");
        sb.append("  \"actions\": [\n");

        java.util.List<dreamdust.talking_weapon.basic_talking_weapon.agent.action.ActionRegistry.LLMActionType> activeActions = new java.util.ArrayList<>();
        for (dreamdust.talking_weapon.basic_talking_weapon.agent.action.ActionRegistry.LLMActionType act : dreamdust.talking_weapon.basic_talking_weapon.agent.action.ActionRegistry.getRegisteredActions()) {
            if (act.getName().equals("favorability_settlement") || act.getName().equals("inner_monologue")) {
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
        
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    @Override
    public String getTaskType() {
        return "Reflection";
    }
}
