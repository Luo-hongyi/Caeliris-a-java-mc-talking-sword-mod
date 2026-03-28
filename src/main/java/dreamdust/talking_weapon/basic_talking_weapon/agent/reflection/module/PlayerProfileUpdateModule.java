package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

/**
 * 对玩家看法更新模块。
 */
public final class PlayerProfileUpdateModule extends ReflectionModule {

    public PlayerProfileUpdateModule() {
        super(ReflectionModuleKey.PLAYER_PROFILE_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        return """
                你负责 `player_profile_update` 模块。
                目标：
                - 基于本次 material、当前状态和相关 persona，重写剑对玩家的低频看法
                约束：
                - `profile_text` 必须是一段可直接提交的稳定描述
                - 不要写分析过程，不要写字段解释
                """.trim();
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();
        JsonObject profileText = new JsonObject();
        profileText.addProperty("type", "string");
        properties.add("profile_text", profileText);

        JsonArray required = new JsonArray();
        required.add("profile_text");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        return new PlayerProfileUpdateResult(requireString(moduleObject, "profile_text"));
    }

    @Override
    public ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input) {
        if (!(result instanceof PlayerProfileUpdateResult typed)) {
            return ReflectionValidationResult.failure("player_profile_update result type mismatch");
        }
        if (typed.profileText().isBlank()) {
            return ReflectionValidationResult.failure("player_profile_update.profile_text must not be blank");
        }
        return ReflectionValidationResult.ok();
    }
}
