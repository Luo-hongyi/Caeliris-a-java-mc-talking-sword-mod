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
 * <li><b>timestamp</b> — 精确的物理时间戳 (毫秒)，用于调试、日志和短时事件排序；不参与 LTM age decay</li>
 * <li><b>associativeOnly</b> — 是否为仅用于联想的隐式标签（默认 false，若为 true 则不在 Prompt
 * 显示，除非匹配性格/记忆）</li>
 * <li><b>crosshairTarget</b> — 是否为玩家当前准心直接指向的目标，用于独立的注意力加分开关</li>
 * <li><b>memoryEligible</b> — 是否允许写入 STM 并参与后续 episode / LTM 融合</li>
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
    private final boolean associativeOnly;
    private final boolean crosshairTarget;
    private final boolean memoryEligible;

    private TaggedContextEntry(Builder builder) {
        this.focusTags = builder.focusTags.isEmpty()
                ? EnumSet.noneOf(FocusTag.class)
                : EnumSet.copyOf(builder.focusTags);
        this.detailTags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.detailTags));
        this.description = builder.description;
        this.baseImportance = builder.baseImportance;
        this.timestamp = builder.timestamp;
        this.associativeOnly = builder.associativeOnly;
        this.crosshairTarget = builder.crosshairTarget;
        this.memoryEligible = builder.memoryEligible;
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

    /** 获取精确的物理时间戳 (毫秒)，用于调试、日志和短时事件排序 */
    public long getTimestamp() {
        return timestamp;
    }

    /** 检查是否属于仅用于联想召回的隐式标签 */
    public boolean isAssociativeOnly() {
        return associativeOnly;
    }

    /** 检查是否为玩家当前准心直接指向的目标 */
    public boolean isCrosshairTarget() {
        return crosshairTarget;
    }

    /** 检查该条目是否允许进入 STM / episode / LTM 的后续记忆链路 */
    public boolean isMemoryEligible() {
        return memoryEligible;
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
                + ", crosshairTarget=" + crosshairTarget
                + ", memoryEligible=" + memoryEligible
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
        private boolean associativeOnly = false;
        private boolean crosshairTarget = false;
        private boolean memoryEligible = true;

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

        /** 设置是否为隐式联想专属标签 */
        public Builder associativeOnly(boolean associativeOnly) {
            this.associativeOnly = associativeOnly;
            return this;
        }

        /** 设置是否为准心直指目标 */
        public Builder crosshairTarget(boolean crosshairTarget) {
            this.crosshairTarget = crosshairTarget;
            return this;
        }

        /** 设置该条目是否允许进入 STM / episode 记忆链 */
        public Builder memoryEligible(boolean memoryEligible) {
            this.memoryEligible = memoryEligible;
            return this;
        }

        public TaggedContextEntry build() {
            return new TaggedContextEntry(this);
        }
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.focusTags.addAll(this.focusTags);
        b.detailTags.addAll(this.detailTags);
        b.description(this.description);
        b.importance(this.baseImportance);
        b.timestamp(this.timestamp);
        b.associativeOnly(this.associativeOnly);
        b.crosshairTarget(this.crosshairTarget);
        b.memoryEligible(this.memoryEligible);
        return b;
    }
}
