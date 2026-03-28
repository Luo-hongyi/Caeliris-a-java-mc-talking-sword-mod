package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

/**
 * 低频好感度更新模块。
 */
public final class FavorabilityUpdateModule extends ReflectionModule {

    public FavorabilityUpdateModule() {
        super(ReflectionModuleKey.FAVORABILITY_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        Integer currentFavorability = input == null ? null : input.state().favorability();
        return """
                你负责 `favorability_update` 模块。
                目标：
                - 基于本次 reflection material 对剑与玩家关系的整体判断，给出一次低频好感度修正
                当前参考：
                - current favorability = %s
                约束：
                - `delta` 为带符号整数，正数表示提高，负数表示降低，0 表示无明显变化
                - `reason` 必须简短说明这次修正的依据
                - 这是低频 reflection 更新，不要按单一瞬时事件过度波动
                """.formatted(currentFavorability == null ? "unknown" : currentFavorability).trim();
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();

        JsonObject delta = new JsonObject();
        delta.addProperty("type", "integer");
        properties.add("delta", delta);

        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        properties.add("reason", reason);

        JsonArray required = new JsonArray();
        required.add("delta");
        required.add("reason");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        if (moduleObject == null || !moduleObject.has("delta") || moduleObject.get("delta").isJsonNull()) {
            throw new IllegalArgumentException("Missing required integer field: delta");
        }
        return new FavorabilityUpdateResult(
                moduleObject.get("delta").getAsInt(),
                requireString(moduleObject, "reason"));
    }

    @Override
    public ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input) {
        if (!(result instanceof FavorabilityUpdateResult typed)) {
            return ReflectionValidationResult.failure("favorability_update result type mismatch");
        }
        if (typed.delta() < -100 || typed.delta() > 100) {
            return ReflectionValidationResult.failure("favorability_update.delta must be within [-100, 100]");
        }
        if (typed.reason().isBlank()) {
            return ReflectionValidationResult.failure("favorability_update.reason must not be blank");
        }
        return ReflectionValidationResult.ok();
    }
}
