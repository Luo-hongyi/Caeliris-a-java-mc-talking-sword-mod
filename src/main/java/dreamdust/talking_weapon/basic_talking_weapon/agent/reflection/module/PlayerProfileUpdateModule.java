package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.prompt.ReflectionPromptResources;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

/**
 * 对玩家看法更新模块。
 */
public final class PlayerProfileUpdateModule extends ReflectionModule {

    private static final String INSTRUCTION_PROMPT = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.MODULE_SHARED_PLAYER_PROFILE_UPDATE_INSTRUCTION).trim();

    public PlayerProfileUpdateModule() {
        super(ReflectionModuleKey.PLAYER_PROFILE_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        return INSTRUCTION_PROMPT;
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();
        JsonObject profileText = new JsonObject();
        profileText.addProperty("type", "string");
        properties.add("profile_text", profileText);
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        properties.add("reason", reason);

        JsonArray required = new JsonArray();
        required.add("profile_text");
        required.add("reason");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        return new PlayerProfileUpdateResult(
                requireString(moduleObject, "profile_text"),
                requireString(moduleObject, "reason"));
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
        if (typed.reason().isBlank()) {
            return ReflectionValidationResult.failure("player_profile_update.reason must not be blank");
        }
        return ReflectionValidationResult.ok();
    }
}
