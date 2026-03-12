package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

import java.util.*;

/**
 * 带标签的上下文条目：Agent 感知系统的标准化数据载体。
 * <p>
 * 每条 {@code TaggedContextEntry} 同时携带：
 * <ul>
 * <li><b>focusTags</b> — 注意力元标签集合，决定该条目属于哪些注意力领域</li>
 * <li><b>detailTags</b> — 细节标签集合，用于记忆检索和偏好匹配</li>
 * <li><b>description</b> — 人类/LLM 可读的事件描述</li>
 * <li><b>baseImportance</b> — 事件的客观重要度 (0.0 ~ 1.0)</li>
 * <li><b>timestamp</b> — 精确的物理时间戳 (毫秒)，用于记忆衰减排序和时序判定</li>
 * </ul>
 * <p>
 * 不可变对象，通过 {@link Builder} 构建。
 */
public final class TaggedContextEntry {

    private final EnumSet<FocusTag> focusTags;
    private final Set<DetailTag> detailTags;
    private final String description;
    private final float baseImportance;
    private final long timestamp;

    private TaggedContextEntry(Builder builder) {
        this.focusTags = builder.focusTags.isEmpty()
                ? EnumSet.noneOf(FocusTag.class)
                : EnumSet.copyOf(builder.focusTags);
        this.detailTags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.detailTags));
        this.description = builder.description;
        this.baseImportance = builder.baseImportance;
        this.timestamp = builder.timestamp;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    /** 获取注意力元标签集合（不可变） */
    public Set<FocusTag> getFocusTags() {
        return Collections.unmodifiableSet(focusTags);
    }

    /** 获取细节标签集合（不可变） */
    public Set<DetailTag> getDetailTags() {
        return detailTags;
    }

    /** 获取人类/LLM 可读的事件描述 */
    public String getDescription() {
        return description;
    }

    /** 获取事件的客观重要度 (0.0 ~ 1.0) */
    public float getBaseImportance() {
        return baseImportance;
    }

    /** 获取精确的物理时间戳 (毫秒)，用于记忆衰减和时序判定 */
    public long getTimestamp() {
        return timestamp;
    }

    // ═══════════════════════════════════════════════════════════════
    // 便捷查询
    // ═══════════════════════════════════════════════════════════════

    /** 检查是否包含指定的注意力元标签 */
    public boolean hasFocus(FocusTag tag) {
        return focusTags.contains(tag);
    }

    /** 检查是否包含指定前缀的细节标签 */
    public boolean hasDetailPrefix(String prefix) {
        for (DetailTag dt : detailTags) {
            if (dt.getPrefix().equals(prefix))
                return true;
        }
        return false;
    }

    /** 获取指定前缀的所有细节标签 */
    public List<DetailTag> getDetailsByPrefix(String prefix) {
        List<DetailTag> result = new ArrayList<>();
        for (DetailTag dt : detailTags) {
            if (dt.getPrefix().equals(prefix))
                result.add(dt);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // Object 方法
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return "TaggedContextEntry{focus=" + focusTags
                + ", details=" + detailTags
                + ", importance=" + baseImportance
                + ", desc=\"" + (description.length() > 50
                        ? description.substring(0, 50) + "..."
                        : description)
                + "\"}";
    }

    // ═══════════════════════════════════════════════════════════════
    // Builder
    // ═══════════════════════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EnumSet<FocusTag> focusTags = EnumSet.noneOf(FocusTag.class);
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();
        private String description = "";
        private float baseImportance = 0.5f;
        private long timestamp = System.currentTimeMillis();

        private Builder() {
        }

        /** 添加一个或多个注意力元标签 */
        public Builder focus(FocusTag... tags) {
            Collections.addAll(focusTags, tags);
            return this;
        }

        /** 添加一个细节标签 */
        public Builder detail(DetailTag tag) {
            detailTags.add(tag);
            return this;
        }

        /** 添加一个细节标签（便捷方式） */
        public Builder detail(String prefix, String value) {
            detailTags.add(DetailTag.of(prefix, value));
            return this;
        }

        /** 设置事件描述 */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** 设置客观重要度 (0.0 ~ 1.0) */
        public Builder importance(float importance) {
            this.baseImportance = Math.max(0f, Math.min(1f, importance));
            return this;
        }

        /** 设置精确时间戳（默认为构建时的 System.currentTimeMillis()） */
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public TaggedContextEntry build() {
            return new TaggedContextEntry(this);
        }
    }
}
