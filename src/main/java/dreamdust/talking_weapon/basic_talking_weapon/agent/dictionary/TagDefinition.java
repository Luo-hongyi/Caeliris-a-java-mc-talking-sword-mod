package dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 标签定义的 POJO。包含标签的自然语言描述，以及附带的额外联想标签列表。
 */
public class TagDefinition {
    private String description;
    private List<String> extras;

    public TagDefinition() {
        this.description = "";
        this.extras = new ArrayList<>();
    }

    public TagDefinition(String description) {
        this.description = description;
        this.extras = new ArrayList<>();
    }

    public TagDefinition(String description, List<String> extras) {
        this.description = description;
        this.extras = extras != null ? new ArrayList<>(extras) : new ArrayList<>();
    }

    public String getDescription() {
        return description != null ? description : "";
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getExtras() {
        return extras != null ? Collections.unmodifiableList(extras) : Collections.emptyList();
    }

    public void setExtras(List<String> extras) {
        this.extras = extras != null ? new ArrayList<>(extras) : new ArrayList<>();
    }
}
