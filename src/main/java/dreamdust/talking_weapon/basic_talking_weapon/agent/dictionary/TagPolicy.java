package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;

/**
 * DetailTag 的系统策略：
 * <ul>
 * <li>自定义标签必须先在描述字典中登记，才能进入 Persona / Memory。</li>
 * <li>{@code native_generic:*} 允许进入系统检索层，但不应直接暴露给 LLM Prompt。</li>
 * </ul>
 */
public final class TagPolicy {

    private TagPolicy() {
    }

    /**
     * 解析用于 Persona / Memory 的标签，并强制校验 custom 标签已登记。
     */
    public static DetailTag parseRegisteredTag(String rawTag) {
        DetailTag tag = DetailTag.parse(rawTag);
        requireRegisteredCustomTag(tag);
        return tag;
    }

    /**
     * 自定义标签必须先在 tag_descriptions.json 中登记。
     */
    public static void requireRegisteredCustomTag(DetailTag tag) {
        if (tag == null || tag.getSource() != DetailTag.TagSource.CUSTOM) {
            return;
        }
        String raw = tag.toString();
        if (!TagDescriptionDictionary.getInstance().contains(raw)) {
            throw new IllegalArgumentException("Unregistered custom DetailTag: " + raw);
        }
    }

    /**
     * Prompt 中隐藏原始 native_generic，保留 native_specific 与 custom_*。
     */
    public static boolean isPromptVisible(DetailTag tag) {
        return tag != null && !DetailTag.NATIVE_GENERIC.equals(tag.getPrefix());
    }
}
