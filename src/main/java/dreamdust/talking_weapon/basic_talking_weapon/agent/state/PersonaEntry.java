package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import com.google.gson.annotations.SerializedName;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persona 条目：将一条人格描述与一个认知领域标签（{@link FocusTag}）绑定，
 * 并可选附带若干 DetailTag（以 {@code prefix:value} 字符串形式存储）。
 * <p>
 * DetailTag 用于联想搜索：根据条目上的细节标签可进一步检索相关记忆或其他条目。
 *
 * <p>
 * <b>示例：</b>
 *
 * <pre>
 * new PersonaEntry("厌恶生食", FocusTag.CONSUMABLE)
 *     .withDetailTag("native_specific:minecraft:rotten_flesh")
 *
 * new PersonaEntry("对岩浆有着本能的恐惧", FocusTag.HAZARD)
 *     .withDetailTag("native_specific:minecraft:lava")
 *     .withDetailTag("custom_specific:health_critical")
 * </pre>
 */
public class PersonaEntry {

    private static final AtomicLong RUNTIME_ID_COUNTER = new AtomicLong();

    /** Studio / 运行时共享的稳定 id */
    private String id;

    /** 是否启用该条 persona */
    private boolean enabled;

    /** 人格 / 偏好的自然语言描述 */
    private String description;

    /** 该条 persona 所属的认知领域（用于按场景筛选） */
    @SerializedName(value = "associated_focus", alternate = {"associatedFocus"})
    private FocusTag associatedFocus;

    /** 是否在该 focus 域下恒定召回 */
    @SerializedName(value = "always_recalled", alternate = {"alwaysRecalled"})
    private boolean alwaysRecalled;

    /**
     * 附带的细节标签列表（以 {@code prefix:value} 原始字符串存储）。
     * <p>
     * 用于联想模式下的二次/三次检索——当该条目被召回时，
     * 系统可沿着这些 DetailTag 进一步查找相关记忆。
     */
    @SerializedName(value = "detail_tags", alternate = {"detailTags"})
    private List<String> detailTags;

    /** Studio 侧备注，默认不注入 prompt。 */
    private String notes;

    /** Gson 反序列化所需的无参构造 */
    public PersonaEntry() {
        this.id = nextRuntimeId();
        this.enabled = true;
        this.detailTags = new ArrayList<>();
        this.notes = "";
    }

    public PersonaEntry(String description, FocusTag associatedFocus) {
        this(nextRuntimeId(), description, associatedFocus);
    }

    public PersonaEntry(String id, String description, FocusTag associatedFocus) {
        this.id = sanitizeId(id);
        this.enabled = true;
        this.description = description;
        this.associatedFocus = associatedFocus;
        this.detailTags = new ArrayList<>();
        this.notes = "";
    }

    // ── 链式构建辅助 ──────────────────────────────────────────────────

    /** 快速追加一条 DetailTag 字符串，返回 this 支持链式调用。 */
    public PersonaEntry withDetailTag(String rawTag) {
        if (rawTag != null && !rawTag.isBlank()) {
            this.detailTags.add(TagPolicy.parseRegisteredTag(rawTag.trim()).toString());
        }
        return this;
    }

    public PersonaEntry withAlwaysRecalled(boolean alwaysRecalled) {
        this.alwaysRecalled = alwaysRecalled;
        return this;
    }

    // ── Getters & Setters ─────────────────────────────────────────────

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = sanitizeId(id);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public FocusTag getAssociatedFocus() {
        return associatedFocus;
    }

    public void setAssociatedFocus(FocusTag associatedFocus) {
        this.associatedFocus = associatedFocus;
    }

    public boolean isAlwaysRecalled() {
        return alwaysRecalled;
    }

    public void setAlwaysRecalled(boolean alwaysRecalled) {
        this.alwaysRecalled = alwaysRecalled;
    }

    public String getNotes() {
        return notes != null ? notes : "";
    }

    public void setNotes(String notes) {
        this.notes = notes != null ? notes : "";
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
        String base = "[" + (associatedFocus != null ? associatedFocus.name() : "UNTAGGED") + "] "
                + (description != null ? description : "");
        if (detailTags != null && !detailTags.isEmpty()) {
            base += " tags:" + detailTags;
        }
        if (alwaysRecalled) {
            base += " [always]";
        }
        return base;
    }

    public boolean isFocusConstant() {
        return alwaysRecalled || detailTags == null || detailTags.isEmpty();
    }

    private static String sanitizeId(String rawId) {
        String value = rawId != null ? rawId.trim() : "";
        return value.isEmpty() ? nextRuntimeId() : value;
    }

    private static String nextRuntimeId() {
        return "runtime_persona_" + RUNTIME_ID_COUNTER.incrementAndGet();
    }
}
