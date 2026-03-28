package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 收紧后的 reflection module。
 * <p>
 * 当前只负责：
 * module instruction prompt / schema / parse / validate。
 */
public abstract class ReflectionModule {

    private final ReflectionModuleKey moduleKey;

    protected ReflectionModule(ReflectionModuleKey moduleKey) {
        this.moduleKey = Objects.requireNonNull(moduleKey, "moduleKey");
    }

    public final ReflectionModuleKey moduleKey() {
        return moduleKey;
    }

    public abstract String buildInstructionPrompt(ReflectionTaskInput input);

    public abstract JsonObject buildOutputContract();

    public abstract ReflectionModuleResult parseResult(JsonObject moduleObject);

    public abstract ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input);

    protected final String requireString(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("Missing required string field: " + fieldName);
        }
        return object.get(fieldName).getAsString();
    }

    protected final Float optionalFloat(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return null;
        }
        return object.get(fieldName).getAsFloat();
    }

    protected final List<Integer> requireIntList(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("Missing required int list field: " + fieldName);
        }
        JsonArray array = object.getAsJsonArray(fieldName);
        List<Integer> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            values.add(element.getAsInt());
        }
        return List.copyOf(values);
    }

    protected final JsonObject requireObject(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            throw new IllegalArgumentException("Missing required object field: " + fieldName);
        }
        return object.getAsJsonObject(fieldName);
    }
}
