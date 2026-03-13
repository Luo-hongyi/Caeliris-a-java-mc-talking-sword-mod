package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;

/**
 * 性格/偏好词条：将一条人格描述与一个认知领域标签（{@link FocusTag}）绑定。
 * <p>
 * 词条由 LLM 在反思任务（ReflectionTask）中动态生成和更新。
 * 在构建 LLM 的 system prompt 时，系统将根据当前上下文中激活的 FocusTag
 * 筛选出相关词条注入，以保证人格呈现的精准性并节省 Token。
 *
 * <p>
 * <b>示例：</b>
 * 
 * <pre>
 * new TraitEntry("厌恶生食", FocusTag.CONSUMABLE)
 * new TraitEntry("对岩浆有着本能的恐惧", FocusTag.HAZARD)
 * new TraitEntry("热衷于战斗厮杀", FocusTag.COMBAT)
 * </pre>
 */
public class TraitEntry {

    /** 性格/偏好的自然语言描述 */
    private String trait;

    /** 该词条所属的认知领域（用于按场景筛选） */
    private FocusTag associatedTag;

    /** Gson 反序列化所需的无参构造 */
    public TraitEntry() {
    }

    public TraitEntry(String trait, FocusTag associatedTag) {
        this.trait = trait;
        this.associatedTag = associatedTag;
    }

    // ---- Getters & Setters ----

    public String getTrait() {
        return trait;
    }

    public void setTrait(String trait) {
        this.trait = trait;
    }

    public FocusTag getAssociatedTag() {
        return associatedTag;
    }

    public void setAssociatedTag(FocusTag associatedTag) {
        this.associatedTag = associatedTag;
    }

    @Override
    public String toString() {
        return "[" + (associatedTag != null ? associatedTag.name() : "UNTAGGED") + "] " + trait;
    }
}
