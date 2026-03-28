package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * memory_synthesis module 的解析结果。
 */
public record MemorySynthesisResult(
        List<Integer> selectedSegmentIndices,
        String ltmText,
        Float importance) implements ReflectionModuleResult {

    public MemorySynthesisResult {
        selectedSegmentIndices = selectedSegmentIndices == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(selectedSegmentIndices));
        ltmText = ltmText == null ? "" : ltmText;
    }
}
