package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

/**
 * 标签定义的 POJO。当前仅维护给 LLM / 人类查看的自然语言描述。
 */
public class TagDefinition {
    private String description;

    public TagDefinition() {
        this.description = "";
    }

    public TagDefinition(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description != null ? description : "";
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
