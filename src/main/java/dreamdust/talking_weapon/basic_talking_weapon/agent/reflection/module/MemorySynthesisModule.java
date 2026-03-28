package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionTaskInput;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * episode synthesis 首批固定 module 之一。
 */
public final class MemorySynthesisModule extends ReflectionModule {

    public MemorySynthesisModule() {
        super(ReflectionModuleKey.MEMORY_SYNTHESIS);
    }

    @Override
    public String buildInstructionPrompt(ReflectionTaskInput input) {
        int segmentCount = input == null ? 0 : input.material().segments().size();
        return """
                你负责 `memory_synthesis` 模块。
                目标：
                - 从当前 reflection material 中挑选最值得沉淀成长记忆的 segment
                - 输出一段统一的 LTM 文本
                约束：
                - `selected_segment_indices` 使用本次 material 的 local segment index
                - 只能选择 0 到 %d 之间的已存在 segment
                - `ltm_text` 必须是可直接提交的总结文本，不要写解释
                - `importance` 可选，若填写则使用 0 到 1 之间的小数
                """.formatted(Math.max(0, segmentCount - 1)).trim();
    }

    @Override
    public JsonObject buildOutputContract() {
        JsonObject properties = new JsonObject();

        JsonObject indices = new JsonObject();
        indices.addProperty("type", "array");
        indices.addProperty("items_type", "integer");
        properties.add("selected_segment_indices", indices);

        JsonObject ltmText = new JsonObject();
        ltmText.addProperty("type", "string");
        properties.add("ltm_text", ltmText);

        JsonObject importance = new JsonObject();
        importance.addProperty("type", "number");
        importance.addProperty("optional", true);
        properties.add("importance", importance);

        JsonArray required = new JsonArray();
        required.add("selected_segment_indices");
        required.add("ltm_text");

        JsonObject contract = new JsonObject();
        contract.addProperty("type", "object");
        contract.add("required", required);
        contract.add("properties", properties);
        return contract;
    }

    @Override
    public ReflectionModuleResult parseResult(JsonObject moduleObject) {
        return new MemorySynthesisResult(
                requireIntList(moduleObject, "selected_segment_indices"),
                requireString(moduleObject, "ltm_text"),
                optionalFloat(moduleObject, "importance"));
    }

    @Override
    public ReflectionValidationResult validateResult(
            ReflectionModuleResult result,
            ReflectionTaskInput input) {
        if (!(result instanceof MemorySynthesisResult typed)) {
            return ReflectionValidationResult.failure("memory_synthesis result type mismatch");
        }
        if (typed.selectedSegmentIndices().isEmpty()) {
            return ReflectionValidationResult.failure("memory_synthesis.selected_segment_indices must not be empty");
        }
        if (typed.ltmText().isBlank()) {
            return ReflectionValidationResult.failure("memory_synthesis.ltm_text must not be blank");
        }

        int segmentCount = input.material().segments().size();
        Set<Integer> dedup = new LinkedHashSet<>();
        for (Integer index : typed.selectedSegmentIndices()) {
            if (index == null) {
                return ReflectionValidationResult.failure("memory_synthesis.selected_segment_indices contains null");
            }
            if (index < 0 || index >= segmentCount) {
                return ReflectionValidationResult.failure(
                        "memory_synthesis.selected_segment_indices contains out-of-range index: " + index);
            }
            dedup.add(index);
        }
        if (dedup.size() != typed.selectedSegmentIndices().size()) {
            return ReflectionValidationResult.failure("memory_synthesis.selected_segment_indices contains duplicates");
        }
        if (typed.importance() != null && (typed.importance() < 0.0f || typed.importance() > 1.0f)) {
            return ReflectionValidationResult.failure("memory_synthesis.importance must be within [0, 1]");
        }
        return ReflectionValidationResult.ok();
    }
}
