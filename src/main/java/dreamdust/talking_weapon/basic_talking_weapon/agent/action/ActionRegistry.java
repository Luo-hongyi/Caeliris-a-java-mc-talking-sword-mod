package dreamdust.talking_weapon.basic_talking_weapon.agent.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动作注册表 (ActionRegistry)
 * <p>
 * 将 LLM 可执行的所有动作类型抽取到统一的注册表中。
 * 在生成发给大语言模型的 System Prompt 时，会自动轮询注册表中的支持类型并生成规范化 JSON 引导字段，
 * 彻底解除核心代码 (如 {@code LLMInteractionTask}) 中对于 "speech", "inner_monologue" 的硬编码耦合。
 * <p>
 * 如果未来需要添加新能力（如攻击、使用物品、释放魔法等），只需调用 {@code register()} 方法注册新的类型解释即可。
 */
public class ActionRegistry {
    
    // 我们目前维护一个静态的列表来保存所有受支持的动作类型。
    private static final List<LLMActionType> REGISTERED_ACTIONS = new ArrayList<>();

    static {
        register(new LLMActionType("inner_monologue", "{ \"type\": \"inner_monologue\", \"content\": \"(剑灵的内心独白，对持有者不可见)\" }"));
        register(new LLMActionType("speech", "{ \"type\": \"speech\", \"content\": \"(对持有者说出口的话)\" }"));
        register(new LLMActionType("favorability_update", "{ \"type\": \"favorability_update\", \"change_amount\": (变动量) } [注：高频行为反馈。提供一个单次事件的基础变动范围（例如：-5到+5，0表示无明显改变）。变动分配与防沉迷约束由系统自动执行]"));
        register(new LLMActionType("favorability_settlement", "{ \"type\": \"favorability_settlement\", \"change_amount\": (变动量) } [注：纪元终结的长期关系总结结算。请综合评估主人的长期行为是否满足了你的潜意识渴望，给出一个较大的总结性好感调整额度（例如：-30到+30）。]"));
    }

    /**
     * 向大语言模型注册一个它能够采取的新行动类型格式范例。
     * @param actionType 包含类型名与一段 JSON 段落示例的配置器
     */
    public static void register(LLMActionType actionType) {
        // 防止重复注册
        for (LLMActionType existing : REGISTERED_ACTIONS) {
            if (existing.getName().equalsIgnoreCase(actionType.getName())) {
                return;
            }
        }
        REGISTERED_ACTIONS.add(actionType);
    }

    /**
     * @return 返回一份目前已注册的所有可执行动作类型的只读副本列表
     */
    public static List<LLMActionType> getRegisteredActions() {
        return Collections.unmodifiableList(REGISTERED_ACTIONS);
    }

    /**
     * 代表一个具备自我描述能力以供注入 Prompt 的 LLM 动作类型信息。
     */
    public static class LLMActionType {
        private final String name;
        private final String formatInstruction;

        /**
         * @param name 动作类型的字符串标记 (例如: "speech")
         * @param formatInstruction 指导大模型生成该动作结构的 JSON 格式字符串示范
         */
        public LLMActionType(String name, String formatInstruction) {
            this.name = name;
            this.formatInstruction = formatInstruction;
        }

        public String getName() {
            return name;
        }

        public String getFormatInstruction() {
            return formatInstruction;
        }
    }
}
