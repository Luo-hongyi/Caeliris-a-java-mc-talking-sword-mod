package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

/**
 * 当前现状总结更新模块。
 */
public final class CurrentSituationUpdateModule extends ReflectionModule {

    public CurrentSituationUpdateModule() {
        super(ReflectionModuleKey.CURRENT_SITUATION_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        return """
                你负责 `current_situation_update` 模块。
                目标：
                - 总结剑当前对整体局势、玩家处境和阶段状态的低频现状判断
                约束：
                - `summary_text` 必须是一段可直接提交的现状总结
                - 不要写推理过程，不要写字段说明
                """.trim();
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();
        JsonObject summaryText = new JsonObject();
        summaryText.addProperty("type", "string");
        properties.add("summary_text", summaryText);

        JsonArray required = new JsonArray();
        required.add("summary_text");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        return new CurrentSituationUpdateResult(requireString(moduleObject, "summary_text"));
    }

    @Override
    public ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input) {
        if (!(result instanceof CurrentSituationUpdateResult typed)) {
            return ReflectionValidationResult.failure("current_situation_update result type mismatch");
        }
        if (typed.summaryText().isBlank()) {
            return ReflectionValidationResult.failure("current_situation_update.summary_text must not be blank");
        }
        return ReflectionValidationResult.ok();
    }
}
