package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

import java.util.Objects;

/**
 * 细节标签 (Detail Tag)：{@code prefix:value} 格式的不可变值对象。
 * <p>
 * 去除了历史上的物理语义（如 entity, item），完全采用结构化前缀命名空间：
 * <ul>
 * <li>{@link #NATIVE_SPECIFIC}: 游戏原生单点数据 (如 minecraft:zombie)</li>
 * <li>{@link #NATIVE_GENERIC}: 游戏原生概括数据 (如 minecraft:undead)</li>
 * <li>{@link #CUSTOM_SPECIFIC}: 自定义单点数据 (如 某个家的路标家)</li>
 * <li>{@link #CUSTOM_GENERIC}: 自定义泛用抽象特征 (如 combat_intense)</li>
 * </ul>
 */
public final class DetailTag {

    public enum TagSource {
        NATIVE, CUSTOM
    }

    public enum TagGranularity {
        SPECIFIC, GENERIC
    }

    // ═══════════════════════════════════════════════════════════════
    // 前缀常量 (Prefix Constants)
    // ═══════════════════════════════════════════════════════════════

    public static final String NATIVE_SPECIFIC = "native_specific";
    public static final String NATIVE_GENERIC = "native_generic";
    public static final String CUSTOM_SPECIFIC = "custom_specific";
    public static final String CUSTOM_GENERIC = "custom_generic";

    // ═══════════════════════════════════════════════════════════════
    // 实例字段
    // ═══════════════════════════════════════════════════════════════

    private final String prefix;
    private final String value;
    private final TagSource source;
    private final TagGranularity granularity;

    private DetailTag(String prefix, String value, TagSource source, TagGranularity granularity) {
        this.prefix = prefix;
        this.value = value;
        this.source = source;
        this.granularity = granularity;
    }

    // ═══════════════════════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════════════════════

    public static DetailTag nativeSpecific(String value) {
        return create(NATIVE_SPECIFIC, value, TagSource.NATIVE, TagGranularity.SPECIFIC);
    }

    public static DetailTag nativeGeneric(String value) {
        return create(NATIVE_GENERIC, value, TagSource.NATIVE, TagGranularity.GENERIC);
    }

    public static DetailTag customSpecific(String value) {
        return create(CUSTOM_SPECIFIC, value, TagSource.CUSTOM, TagGranularity.SPECIFIC);
    }

    public static DetailTag customGeneric(String value) {
        return create(CUSTOM_GENERIC, value, TagSource.CUSTOM, TagGranularity.GENERIC);
    }

    public static DetailTag parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("DetailTag raw string must not be empty");
        }
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("DetailTag must be in prefix:value format: " + raw);
        }
        return of(parts[0], parts[1]);
    }

    public static DetailTag of(String prefix, String value) {
        TagSource s = TagSource.CUSTOM;
        TagGranularity g = TagGranularity.GENERIC;

        switch (prefix) {
            case NATIVE_SPECIFIC:
                s = TagSource.NATIVE;
                g = TagGranularity.SPECIFIC;
                break;
            case NATIVE_GENERIC:
                s = TagSource.NATIVE;
                g = TagGranularity.GENERIC;
                break;
            case CUSTOM_SPECIFIC:
                s = TagSource.CUSTOM;
                g = TagGranularity.SPECIFIC;
                break;
            case CUSTOM_GENERIC:
                s = TagSource.CUSTOM;
                g = TagGranularity.GENERIC;
                break;
            default:
                throw new IllegalArgumentException("Unknown DetailTag prefix: " + prefix);
        }

        return create(prefix, value, s, g);
    }

    private static DetailTag create(String prefix, String v, TagSource s, TagGranularity g) {
        String normalizedPrefix = prefix == null ? null : prefix.trim();
        String normalizedValue = v == null ? null : v.trim();
        if (normalizedPrefix == null || normalizedPrefix.isEmpty()) {
            throw new IllegalArgumentException("DetailTag prefix must not be empty");
        }
        if (normalizedValue == null || normalizedValue.isEmpty()) {
            throw new IllegalArgumentException("DetailTag value must not be empty");
        }
        return new DetailTag(normalizedPrefix, normalizedValue, s, g);
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getPrefix() {
        return prefix;
    }

    public String getValue() {
        return value;
    }

    public TagSource getSource() {
        return source;
    }

    public TagGranularity getGranularity() {
        return granularity;
    }

    public boolean isSpecific() {
        return granularity == TagGranularity.SPECIFIC;
    }

    public boolean isGeneric() {
        return granularity == TagGranularity.GENERIC;
    }

    // ═══════════════════════════════════════════════════════════════
    // Object 方法
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return prefix + ":" + value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DetailTag)) return false;
        DetailTag that = (DetailTag) o;
        return prefix.equals(that.prefix) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, value);
    }
}
