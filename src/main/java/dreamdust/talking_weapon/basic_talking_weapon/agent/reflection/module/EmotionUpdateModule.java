package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

/**
 * 低频情绪刷新模块。
 */
public final class EmotionUpdateModule extends ReflectionModule {

    public EmotionUpdateModule() {
        super(ReflectionModuleKey.EMOTION_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        String currentEmotion = input == null ? "" : input.state().primaryEmotion();
        Integer currentIntensity = input == null ? null : input.state().emotionIntensity();
        return """
                你负责 `emotion_update` 模块。
                目标：
                - 基于本次 reflection material 更新剑当前的主导情绪与强度
                当前参考：
                - current emotion = %s
                - current intensity = %s
                约束：
                - `emotion` 必须使用系统支持的情绪枚举名
                - `intensity` 使用 0 到 100 的整数
                - `reason` 简短说明这次情绪判断的依据
                """.formatted(
                currentEmotion == null || currentEmotion.isBlank() ? "unknown" : currentEmotion,
                currentIntensity == null ? "unknown" : currentIntensity).trim();
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();

        JsonObject emotion = new JsonObject();
        emotion.addProperty("type", "string");
        properties.add("emotion", emotion);

        JsonObject intensity = new JsonObject();
        intensity.addProperty("type", "integer");
        properties.add("intensity", intensity);

        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        properties.add("reason", reason);

        JsonArray required = new JsonArray();
        required.add("emotion");
        required.add("intensity");
        required.add("reason");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        if (moduleObject == null || !moduleObject.has("intensity") || moduleObject.get("intensity").isJsonNull()) {
            throw new IllegalArgumentException("Missing required integer field: intensity");
        }
        return new EmotionUpdateResult(
                requireString(moduleObject, "emotion"),
                moduleObject.get("intensity").getAsInt(),
                requireString(moduleObject, "reason"));
    }

    @Override
    public ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input) {
        if (!(result instanceof EmotionUpdateResult typed)) {
            return ReflectionValidationResult.failure("emotion_update result type mismatch");
        }
        if (typed.emotion().isBlank()) {
            return ReflectionValidationResult.failure("emotion_update.emotion must not be blank");
        }
        try {
            AgentState.PrimaryEmotion.valueOf(typed.emotion().trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ReflectionValidationResult.failure("emotion_update.emotion is not a supported enum name");
        }
        if (typed.intensity() < 0 || typed.intensity() > 100) {
            return ReflectionValidationResult.failure("emotion_update.intensity must be within [0, 100]");
        }
        if (typed.reason().isBlank()) {
            return ReflectionValidationResult.failure("emotion_update.reason must not be blank");
        }
        return ReflectionValidationResult.ok();
    }
}
