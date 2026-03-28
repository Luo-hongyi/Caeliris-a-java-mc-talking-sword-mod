package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

/**
 * favorability_update module 的解析结果。
 */
public record FavorabilityUpdateResult(
        int delta,
        String reason) implements ReflectionModuleResult {

    public FavorabilityUpdateResult {
        reason = reason == null ? "" : reason;
    }
}
