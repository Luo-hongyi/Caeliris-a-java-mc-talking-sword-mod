package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.prompt.ReflectionPromptResources;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

/**
 * 阶段性前情提要更新模块。
 */
public final class RecapUpdateModule extends ReflectionModule {

    private static final String INSTRUCTION_PROMPT = ReflectionPromptResources.loadRequired(
            ReflectionPromptResources.MODULE_SHARED_RECAP_UPDATE_INSTRUCTION).trim();

    public RecapUpdateModule() {
        super(ReflectionModuleKey.RECAP_UPDATE);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        return INSTRUCTION_PROMPT;
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
