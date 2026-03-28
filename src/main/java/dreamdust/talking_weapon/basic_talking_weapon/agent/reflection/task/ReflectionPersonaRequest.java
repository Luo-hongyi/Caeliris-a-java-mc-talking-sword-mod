package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

/**
 * ReflectionTask 需要怎样召回 persona 条目。
 * <p>
 * persona 在这里严格指 {@code PersonaEntry}，
 * 不包含 favorability / playerProfile / identity 等其他状态字段。
 */
public record ReflectionPersonaRequest(
        boolean includeMaterialMatchedEntries,
        boolean includeAlwaysRecalledEntries) {
}
