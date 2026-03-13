package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;

import java.util.*;

/**
 * 剑视角的深度记忆切片：一个不可变的"经历快照"。
 * <p>
 * 由后台 ReflectionTask 将 {@link ActionBuffer} 中积累的动作流
 * 和近期对话打包后，发给 LLM 沉思生成。
 * <p>
 * <b>Recall 评分</b>（在 AgentMemory 中实现，本类不含 recall 逻辑）：
 * <ul>
 * <li>FocusTag 重合度 — 当前场景与记忆的注意力域匹配</li>
 * <li>DetailTag 精确/前缀匹配 — 具体实体/群系/状态联想</li>
 * <li>importance 加权 — 重大事件始终优先</li>
 * <li>vividness 动态计算 — 基于 (currentTime - timestamp) 和 importance，
 * 不存储在本对象中，在 recall 调用时现算</li>
 * </ul>
 *
 * <pre>
 * vividness = max(0.1, 1.0 - hoursElapsed / HALF_LIFE)
 * importance > 0.8 的记忆衰减速度减半（刻骨铭心）
 * </pre>
 */
public final class MemoryRecord {

    private final UUID id;
    private final long timestamp;
    private final String era;
    private final String triggerSummary;

    private final EnumSet<FocusTag> focusTags;
    private final Set<DetailTag> detailTags;

    private final String innerMonologue;
    private final String speech; // 可为 null
    private final float importance; // 0.0 ~ 1.0

    private MemoryRecord(Builder builder) {
        this.id = UUID.randomUUID();
        this.timestamp = builder.timestamp;
        this.era = builder.era;
        this.triggerSummary = builder.triggerSummary;
        this.focusTags = builder.focusTags.isEmpty()
                ? EnumSet.noneOf(FocusTag.class)
                : EnumSet.copyOf(builder.focusTags);
        this.detailTags = Collections.unmodifiableSet(new LinkedHashSet<>(builder.detailTags));
        this.innerMonologue = builder.innerMonologue;
        this.speech = builder.speech;
        this.importance = Math.max(0f, Math.min(1f, builder.importance));
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public UUID getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getEra() {
        return era;
    }

    public String getTriggerSummary() {
        return triggerSummary;
    }

    public Set<FocusTag> getFocusTags() {
        return Collections.unmodifiableSet(focusTags);
    }

    public Set<DetailTag> getDetailTags() {
        return detailTags;
    }

    public String getInnerMonologue() {
        return innerMonologue;
    }

    public String getSpeech() {
        return speech;
    }

    public float getImportance() {
        return importance;
    }

    // ═══════════════════════════════════════════════════════════════
    // 便捷查询
    // ═══════════════════════════════════════════════════════════════

    public boolean hasSpeech() {
        return speech != null && !speech.isBlank();
    }

    public boolean hasFocus(FocusTag tag) {
        return focusTags.contains(tag);
    }

    public boolean hasDetailPrefix(String prefix) {
        for (DetailTag dt : detailTags) {
            if (dt.getPrefix().equals(prefix))
                return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Object
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return "MemoryRecord{id=" + id
                + ", focus=" + focusTags
                + ", importance=" + importance
                + ", speech=" + (hasSpeech() ? "'" + speech + "'" : "(沉默)")
                + "}";
    }

    // ═══════════════════════════════════════════════════════════════
    // Builder
    // ═══════════════════════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long timestamp = System.currentTimeMillis();
        private String era = "current_journey";
        private String triggerSummary = "";
        private final EnumSet<FocusTag> focusTags = EnumSet.noneOf(FocusTag.class);
        private final Set<DetailTag> detailTags = new LinkedHashSet<>();
        private String innerMonologue = "";
        private String speech;
        private float importance = 0.5f;

        private Builder() {
        }

        public Builder timestamp(long ts) {
            this.timestamp = ts;
            return this;
        }

        public Builder era(String era) {
            this.era = era;
            return this;
        }

        public Builder triggerSummary(String s) {
            this.triggerSummary = s;
            return this;
        }

        public Builder focus(FocusTag... tags) {
            Collections.addAll(focusTags, tags);
            return this;
        }

        public Builder focusTags(Set<FocusTag> tags) {
            focusTags.addAll(tags);
            return this;
        }

        public Builder detail(DetailTag tag) {
            detailTags.add(tag);
            return this;
        }

        public Builder detail(String prefix, String v) {
            detailTags.add(DetailTag.of(prefix, v));
            return this;
        }

        public Builder detailTags(Set<DetailTag> tags) {
            detailTags.addAll(tags);
            return this;
        }

        public Builder innerMonologue(String m) {
            this.innerMonologue = m;
            return this;
        }

        public Builder speech(String s) {
            this.speech = s;
            return this;
        }

        public Builder importance(float imp) {
            this.importance = imp;
            return this;
        }

        public MemoryRecord build() {
            return new MemoryRecord(this);
        }
    }
}
