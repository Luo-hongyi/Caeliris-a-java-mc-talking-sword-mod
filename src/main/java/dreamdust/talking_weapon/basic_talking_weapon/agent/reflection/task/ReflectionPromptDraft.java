package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

/**
 * ReflectionMaterial 的 prompt-facing 投影。
 * <p>
 * 结构化 material 与 prompt 文本并存，但语义上后者只是前者的渲染结果。
 */
public record ReflectionPromptDraft(
        String draftText,
        String segmentIndexMap) {

    public ReflectionPromptDraft {
        draftText = draftText == null ? "" : draftText;
        segmentIndexMap = segmentIndexMap == null ? "" : segmentIndexMap;
    }
}
