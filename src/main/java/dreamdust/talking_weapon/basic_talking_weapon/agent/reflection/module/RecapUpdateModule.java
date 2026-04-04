package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

/**
 * 阶段性前情提要更新模块。
 */
public final class RecapUpdateModule extends ReflectionModule {

    public RecapUpdateModule() {
        super(ReflectionModuleKey.RECAP_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        return """
                你负责 `recap_update` 模块。
                目标：
                - 基于过去一段时间的记录，为剑生成一段阶段性“前情提要”
                - 这不是即时状态播报，而是帮助后续理解最近大背景的低频摘要
                约束：
                - `recap_text` 必须是一段可直接提交的前情提要
                - 允许概括阶段趋势，但不要伪装成“此刻正在发生”的即时事实
                - 不要写推理过程，不要写字段说明
                """.trim();
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();
        JsonObject recapText = new JsonObject();
        recapText.addProperty("type", "string");
        properties.add("recap_text", recapText);

        JsonArray required = new JsonArray();
        required.add("recap_text");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        return new RecapUpdateResult(requireString(moduleObject, "recap_text"));
    }

    @Override
    public ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input) {
        if (!(result instanceof RecapUpdateResult typed)) {
            return ReflectionValidationResult.failure("recap_update result type mismatch");
        }
        if (typed.recapText().isBlank()) {
            return ReflectionValidationResult.failure("recap_update.recap_text must not be blank");
        }
        return ReflectionValidationResult.ok();
    }
}
