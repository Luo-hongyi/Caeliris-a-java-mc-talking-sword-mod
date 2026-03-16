package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 性格/偏好词条：将一条人格描述与一个认知领域标签（{@link FocusTag}）绑定，
 * 并可选附带若干 DetailTag（以 {@code prefix:value} 字符串形式存储）。
 * <p>
 * DetailTag 用于联想搜索：根据词条上的细节标签可进一步检索相关记忆或其他词条。
 *
 * <p>
 * <b>示例：</b>
 *
 * <pre>
 * new TraitEntry("厌恶生食", FocusTag.CONSUMABLE)
 *     .withDetailTag("native_specific:minecraft:rotten_flesh")
 *
 * new TraitEntry("对岩浆有着本能的恐惧", FocusTag.HAZARD)
 *     .withDetailTag("native_specific:minecraft:lava")
 *     .withDetailTag("custom_specific:health_critical")
 * </pre>
 */
public class TraitEntry {

    /** 性格/偏好的自然语言描述 */
    private String trait;

    /** 该词条所属的认知领域（用于按场景筛选） */
    private FocusTag associatedTag;

    /**
     * 附带的细节标签列表（以 {@code prefix:value} 原始字符串存储）。
     * <p>
     * 用于联想模式下的二次/三次检索——当该词条被召回时，
     * 系统可沿着这些 DetailTag 进一步查找相关记忆。
     */
    private List<String> detailTags;

    /** Gson 反序列化所需的无参构造 */
    public TraitEntry() {
        this.detailTags = new ArrayList<>();
    }

    public TraitEntry(String trait, FocusTag associatedTag) {
        this.trait = trait;
        this.associatedTag = associatedTag;
        this.detailTags = new ArrayList<>();
    }

    // ── 链式构建辅助 ──────────────────────────────────────────────────

    /** 快速追加一条 DetailTag 字符串，返回 this 支持链式调用。 */
    public TraitEntry withDetailTag(String rawTag) {
        if (rawTag != null && !rawTag.isBlank()) {
            this.detailTags.add(TagPolicy.parseRegisteredTag(rawTag.trim()).toString());
        }
        return this;
    }

    // ── Getters & Setters ─────────────────────────────────────────────

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

    // ── DetailTags ────────────────────────────────────────────────────

    /** 返回不可修改的 DetailTag 字符串列表。 */
    public List<String> getDetailTags() {
        return detailTags == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(detailTags);
    }

    /** 整体替换 DetailTag 列表。 */
    public void setDetailTags(List<String> tags) {
        this.detailTags = new ArrayList<>();
        if (tags == null) {
            return;
        }
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                this.detailTags.add(TagPolicy.parseRegisteredTag(tag.trim()).toString());
            }
        }
    }

    /**
     * 返回逗号分隔的 DetailTag 字符串（供 GUI 编辑框显示）。
     * <p>
     * 示例：{@code "native_specific:minecraft:zombie, custom_specific:health_critical"}
     */
    public String getDetailTagsAsString() {
        if (detailTags == null || detailTags.isEmpty())
            return "";
        return String.join(", ", detailTags);
    }

    /**
     * 从逗号分隔字符串解析并写入 DetailTag 列表（GUI 保存时使用）。
     * 每条以逗号分隔，自动 trim 并过滤空白条目。
     */
    public void setDetailTagsFromString(String csv) {
        List<String> parsed = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(TagPolicy.parseRegisteredTag(trimmed).toString());
                }
            }
        }
        this.detailTags = parsed;
    }

    // ── toString ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        String base = "[" + (associatedTag != null ? associatedTag.name() : "UNTAGGED") + "] " + trait;
        if (detailTags != null && !detailTags.isEmpty()) {
            base += " tags:" + detailTags;
        }
        return base;
    }
}
