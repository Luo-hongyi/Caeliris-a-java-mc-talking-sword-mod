package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 长期记忆切片 (Long-Term Memory Entry)。
 * <p>
 * 人为偏好设定和游戏内总结的往事统一结构。
 * 利用 FocusTag 和 DetailTag 结合 AttentionFilter 进行 Top-K 提取。
 */
public final class LTMEntry {

    public enum SourceType {
        IMPLANTED,    // 人为植入，不随时间遗忘
        SYNTHESIZED   // 游戏中总结，会随时间衰退
    }

    private final UUID id;
    
    // ═══════════════════════════════════════════════════════════════
    // 1. 核心内容区 (统一检索的基础)
    // ═══════════════════════════════════════════════════════════════
    private final String description;       // 记忆的实质文本
    private final EnumSet<FocusTag> focusTags;  // 主检索标签 (如 COMBAT, SOCIAL)
    private final Set<DetailTag> detailTags; // 细节交叉标签 (如 ZOMBIE, CAVE)

    // ═══════════════════════════════════════════════════════════════
    // 2. 生命与归属区 (区分行为模式)
    // ═══════════════════════════════════════════════════════════════
    private final SourceType sourceType;    // IMPLANTED vs SYNTHESIZED
    private final long timestamp;           // 发生时间。IMPLANTED 时为 -1

    // ═══════════════════════════════════════════════════════════════
    // 3. 权重与遗忘区 (决定 Top-K 选拔和衰退速度)
    // ═══════════════════════════════════════════════════════════════
    private final float importance;         // 初始核心权重 (0.0 - 1.0)
    private float clarity;                  // 动态清晰度指标
    private long lastAccessedAt;            // 最后一次被回忆时间 (用于重置 clarity 或衰减算法)

    private LTMEntry(Builder builder) {
        this.id = UUID.randomUUID();
        this.description = builder.description;
        this.focusTags = builder.focusTags.isEmpty()
                ? EnumSet.noneOf(FocusTag.class)
                : EnumSet.copyOf(builder.focusTags);
        this.detailTags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.detailTags));
        
        this.sourceType = builder.sourceType;
        this.timestamp = builder.sourceType == SourceType.IMPLANTED ? -1L : builder.timestamp;
        
        this.importance = Math.max(0f, Math.min(1f, builder.importance));
        // 新建时，默认 clarity 与 importance 一致，或者明确赋值
        this.clarity = builder.clarity >= 0 ? builder.clarity : this.importance;
        this.lastAccessedAt = builder.lastAccessedAt > 0 ? builder.lastAccessedAt : this.timestamp;
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public String getDescription() { return description; }
    public Set<FocusTag> getFocusTags() { return Collections.unmodifiableSet(focusTags); }
    public Set<DetailTag> getDetailTags() { return detailTags; }
    public SourceType getSourceType() { return sourceType; }
    public long getTimestamp() { return timestamp; }
    public float getImportance() { return importance; }
    public float getClarity() { return clarity; }
    public long getLastAccessedAt() { return lastAccessedAt; }

    // --- Actions ---

    /**
     * 更新最后一次访问时间，有助于减缓遗忘
     */
    public void markAccessed(long currentTime) {
        this.lastAccessedAt = currentTime;
    }

    /**
     * 更新清晰度
     */
    public void setClarity(float newClarity) {
        this.clarity = Math.max(0f, Math.min(1f, newClarity));
    }
    
    // --- 便捷查询 ---
    
    public boolean hasFocus(FocusTag tag) {
        return focusTags.contains(tag);
    }

    public boolean hasDetailPrefix(String prefix) {
        for (DetailTag dt : detailTags) {
            if (dt.getPrefix().equals(prefix)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "LTMEntry{id=" + id
                + ", type=" + sourceType
                + ", focus=" + focusTags
                + ", imp=" + importance
                + ", desc='" + (description != null && description.length() > 20 ? description.substring(0, 20) + "..." : description) + "'"
                + "}";
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String description = "";
        private final EnumSet<FocusTag> focusTags = EnumSet.noneOf(FocusTag.class);
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();
        private SourceType sourceType = SourceType.SYNTHESIZED;
        private long timestamp = System.currentTimeMillis();
        private float importance = 0.5f;
        private float clarity = -1f;
        private long lastAccessedAt = -1L;

        private Builder() {}

        public Builder description(String d) { this.description = d; return this; }
        public Builder sourceType(SourceType t) { this.sourceType = t; return this; }
        public Builder timestamp(long ts) { this.timestamp = ts; return this; }
        public Builder lastAccessedAt(long ts) { this.lastAccessedAt = ts; return this; }
        
        public Builder focus(FocusTag... tags) { Collections.addAll(focusTags, tags); return this; }
        public Builder focusTags(Set<FocusTag> tags) { focusTags.addAll(tags); return this; }
        
        public Builder detail(DetailTag tag) { detailTags.add(tag); return this; }
        public Builder detail(String prefix, String v) { detailTags.add(DetailTag.of(prefix, v)); return this; }
        public Builder detailTags(Set<DetailTag> tags) { detailTags.addAll(tags); return this; }
        
        public Builder importance(float imp) { this.importance = imp; return this; }
        public Builder clarity(float c) { this.clarity = c; return this; }

        public LTMEntry build() {
            return new LTMEntry(this);
        }
    }
}
