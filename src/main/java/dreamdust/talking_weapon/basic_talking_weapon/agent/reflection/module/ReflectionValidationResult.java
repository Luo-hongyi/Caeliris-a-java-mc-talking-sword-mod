package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Module 单体验证结果。
 */
public record ReflectionValidationResult(
        boolean valid,
        List<String> messages) {

    public ReflectionValidationResult {
        messages = messages == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public static ReflectionValidationResult ok() {
        return new ReflectionValidationResult(true, List.of());
    }

    public static ReflectionValidationResult failure(String message) {
        return new ReflectionValidationResult(false, List.of(message == null ? "validation failed" : message));
    }

    public static ReflectionValidationResult failure(List<String> messages) {
        return new ReflectionValidationResult(false, messages);
    }
}
