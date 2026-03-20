package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.WorldTimeSemantics;
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
    private final DetailTag semanticTimeTag; // 独立时间语义标签（不并入普通 detail cue）
    private final Set<DetailTag> associativeDetailTags; // 联想图使用的标签视图（detailTags + semanticTimeTag）

    // ═══════════════════════════════════════════════════════════════
    // 2. 生命与归属区 (区分行为模式)
    // ═══════════════════════════════════════════════════════════════
    private final SourceType sourceType;    // IMPLANTED vs SYNTHESIZED
    private final long timestamp;           // 兼容旧字段：创建/发生时间。IMPLANTED 时为 -1，不参与 recall age decay
    private final long occurredGameTime;    // 内部使用的单调绝对游戏时间，用于排序与衰退；可由 dayIndex + timeOfDay 重建
    private final long occurredDayIndex;    // 长期存储的第几天（1-based），用于叙事锚点
    private final long occurredTimeOfDayTick; // 长期存储的当日时段 tick，用于叙事锚点
    private final String semanticTimeLabel; // 例如：第23天的深夜 (02:30)
    private final String dimensionId;       // 例如：minecraft:overworld
    private final String biomeId;           // 例如：minecraft:plains
    private final Boolean raining;          // 该记忆发生时是否下雨

    // ═══════════════════════════════════════════════════════════════
    // 3. 权重与遗忘区 (决定 Top-K 选拔和衰退速度)
    // ═══════════════════════════════════════════════════════════════
    private final float importance;         // 初始核心权重 (0.0 - 1.0)
    private long lastAccessedAt;            // 最后一次被回忆时间（当前仅记录，预留给未来的记忆维护逻辑）

    private LTMEntry(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.description = builder.description;
        this.focusTags = builder.focusTags.isEmpty()
                ? EnumSet.noneOf(FocusTag.class)
                : EnumSet.copyOf(builder.focusTags);
        this.detailTags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.detailTags));
        this.semanticTimeTag = builder.semanticTimeTag;
        LinkedHashSet<DetailTag> associativeTags = new LinkedHashSet<>(builder.detailTags);
        if (builder.semanticTimeTag != null) {
            associativeTags.add(builder.semanticTimeTag);
        }
        this.associativeDetailTags = Collections.unmodifiableSet(associativeTags);
        
        this.sourceType = builder.sourceType;
        this.timestamp = builder.sourceType == SourceType.IMPLANTED ? -1L : builder.timestamp;
        long resolvedOccurredGameTime = builder.occurredGameTime;
        long resolvedOccurredDayIndex = builder.occurredDayIndex;
        long resolvedOccurredTimeOfDayTick = builder.occurredTimeOfDayTick;

        if (resolvedOccurredDayIndex < 0L && resolvedOccurredGameTime >= 0L) {
            resolvedOccurredDayIndex = WorldTimeSemantics.dayIndex(resolvedOccurredGameTime);
        }
        if (resolvedOccurredTimeOfDayTick < 0L && resolvedOccurredGameTime >= 0L) {
            resolvedOccurredTimeOfDayTick = WorldTimeSemantics.timeOfDay(resolvedOccurredGameTime);
        }
        if (resolvedOccurredGameTime < 0L
                && resolvedOccurredDayIndex > 0L
                && resolvedOccurredTimeOfDayTick >= 0L) {
            resolvedOccurredGameTime = WorldTimeSemantics.composeAbsoluteGameTime(
                    resolvedOccurredDayIndex,
                    resolvedOccurredTimeOfDayTick);
        }

        this.occurredGameTime = resolvedOccurredGameTime;
        this.occurredDayIndex = resolvedOccurredDayIndex;
        this.occurredTimeOfDayTick = resolvedOccurredTimeOfDayTick;
        this.semanticTimeLabel = builder.semanticTimeLabel != null
                ? builder.semanticTimeLabel
                : WorldTimeSemantics.describeMemoryTimeAnchor(
                        resolvedOccurredDayIndex,
                        resolvedOccurredTimeOfDayTick);
        this.dimensionId = builder.dimensionId;
        this.biomeId = builder.biomeId;
        this.raining = builder.raining;
        
        this.importance = Math.max(0f, Math.min(1f, builder.importance));
        this.lastAccessedAt = builder.lastAccessedAt > 0 ? builder.lastAccessedAt : this.timestamp;
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public String getDescription() { return description; }
    public Set<FocusTag> getFocusTags() { return Collections.unmodifiableSet(focusTags); }
    public Set<DetailTag> getDetailTags() { return detailTags; }
    public DetailTag getSemanticTimeTag() { return semanticTimeTag; }
    public Set<DetailTag> getAssociativeDetailTags() { return associativeDetailTags; }
    public SourceType getSourceType() { return sourceType; }
    public long getTimestamp() { return timestamp; }
    public long getOccurredGameTime() { return occurredGameTime; }
    public long getOccurredDayIndex() { return occurredDayIndex; }
    public long getOccurredTimeOfDayTick() { return occurredTimeOfDayTick; }
    public String getSemanticTimeLabel() { return semanticTimeLabel; }
    public String getDimensionId() { return dimensionId; }
    public String getBiomeId() { return biomeId; }
    public Boolean getRaining() { return raining; }
    public float getImportance() { return importance; }
    public long getLastAccessedAt() { return lastAccessedAt; }

    // --- Actions ---

    /**
     * 记录最后一次被回忆的时间戳，当前主要用于保留运行时轨迹。
     */
    public void markAccessed(long currentTime) {
        this.lastAccessedAt = currentTime;
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

    /**
     * 供日志和 Prompt 使用的轻量环境锚点。
     */
    public String describeSemanticAnchor() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (semanticTimeLabel != null && !semanticTimeLabel.isBlank()) {
            parts.add(semanticTimeLabel);
        }
        if (Boolean.TRUE.equals(raining)) {
            parts.add("雨中");
        }
        if (dimensionId != null && !dimensionId.isBlank()) {
            parts.add("维度:" + dimensionId);
        }
        if (biomeId != null && !biomeId.isBlank() && !"unknown".equalsIgnoreCase(biomeId)) {
            parts.add("群系:" + biomeId);
        }
        return String.join(" / ", parts);
    }

    @Override
    public String toString() {
        return "LTMEntry{id=" + id
                + ", type=" + sourceType
                + ", focus=" + focusTags
                + ", imp=" + importance
                + (semanticTimeTag != null ? ", semanticTimeTag=" + semanticTimeTag : "")
                + (semanticTimeLabel != null && !semanticTimeLabel.isBlank() ? ", time='" + semanticTimeLabel + "'" : "")
                + ", desc='" + (description != null && description.length() > 20 ? description.substring(0, 20) + "..." : description) + "'"
                + "}";
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String description = "";
        private final EnumSet<FocusTag> focusTags = EnumSet.noneOf(FocusTag.class);
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();
        private DetailTag semanticTimeTag;
        private SourceType sourceType = SourceType.SYNTHESIZED;
        private long timestamp = System.currentTimeMillis();
        private long occurredGameTime = -1L;
        private long occurredDayIndex = -1L;
        private long occurredTimeOfDayTick = -1L;
        private String semanticTimeLabel;
        private String dimensionId;
        private String biomeId;
        private Boolean raining;
        private float importance = 0.5f;
        private long lastAccessedAt = -1L;

        private Builder() {}

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder description(String d) { this.description = d; return this; }
        public Builder sourceType(SourceType t) { this.sourceType = t; return this; }
        public Builder timestamp(long ts) { this.timestamp = ts; return this; }
        public Builder occurredGameTime(long gameTime) { this.occurredGameTime = gameTime; return this; }
        public Builder occurredDayIndex(long dayIndex) { this.occurredDayIndex = dayIndex; return this; }
        public Builder occurredTimeOfDayTick(long tick) { this.occurredTimeOfDayTick = tick; return this; }
        public Builder semanticTimeLabel(String label) { this.semanticTimeLabel = label; return this; }
        public Builder semanticTimeTag(DetailTag tag) { this.semanticTimeTag = tag; return this; }
        public Builder dimensionId(String dimensionId) { this.dimensionId = dimensionId; return this; }
        public Builder biomeId(String biomeId) { this.biomeId = biomeId; return this; }
        public Builder raining(Boolean raining) { this.raining = raining; return this; }
        public Builder lastAccessedAt(long ts) { this.lastAccessedAt = ts; return this; }
        
        public Builder focus(FocusTag... tags) { Collections.addAll(focusTags, tags); return this; }
        public Builder focusTags(Set<FocusTag> tags) { focusTags.addAll(tags); return this; }
        
        public Builder detail(DetailTag tag) { detailTags.add(tag); return this; }
        public Builder detail(String prefix, String v) { detailTags.add(DetailTag.of(prefix, v)); return this; }
        public Builder detailTags(Set<DetailTag> tags) { detailTags.addAll(tags); return this; }
        
        public Builder importance(float imp) { this.importance = imp; return this; }

        public LTMEntry build() {
            return new LTMEntry(this);
        }
    }
}
