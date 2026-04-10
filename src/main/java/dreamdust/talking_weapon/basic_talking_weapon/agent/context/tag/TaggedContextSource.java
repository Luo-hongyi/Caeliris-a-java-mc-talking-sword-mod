package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

import java.util.Objects;

/**
 * TaggedContext 条目的结构化主体来源。
 * <p>
 * sourceId 应尽量使用稳定标识符：
 * <ul>
 * <li>PLAYER: 玩家 UUID</li>
 * <li>AGENT: 当前固定为 self</li>
 * <li>ENVIRONMENT: 当前固定为 environment</li>
 * </ul>
 * displayName 仅用于调试 / 可读展示，不应作为业务主键。
 */
public final class TaggedContextSource {

    private final TaggedContextSourceType type;
    private final String sourceId;
    private final String displayName;

    private TaggedContextSource(
            TaggedContextSourceType type,
            String sourceId,
            String displayName) {
        this.type = Objects.requireNonNull(type, "type");
        this.sourceId = normalizeId(sourceId);
        this.displayName = normalizeDisplayName(displayName, this.sourceId);
    }

    public static TaggedContextSource player(String playerId, String displayName) {
        return new TaggedContextSource(TaggedContextSourceType.PLAYER, playerId, displayName);
    }

    public static TaggedContextSource agent(String sourceId, String displayName) {
        return new TaggedContextSource(TaggedContextSourceType.AGENT, sourceId, displayName);
    }

    public static TaggedContextSource agentSelf() {
        return agent("self", "self");
    }

    public static TaggedContextSource environment(String sourceId, String displayName) {
        return new TaggedContextSource(TaggedContextSourceType.ENVIRONMENT, sourceId, displayName);
    }

    public static TaggedContextSource environment() {
        return environment("environment", "environment");
    }

    public TaggedContextSourceType getType() {
        return type;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return "TaggedContextSource{"
                + "type=" + type
                + ", sourceId='" + sourceId + '\''
                + ", displayName='" + displayName + '\''
                + '}';
    }

    private static String normalizeId(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("TaggedContextSource sourceId must not be blank");
        }
        return sourceId.trim();
    }

    private static String normalizeDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isBlank()) {
            return fallback;
        }
        return displayName.trim();
    }
}
