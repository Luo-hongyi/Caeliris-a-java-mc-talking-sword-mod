package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

/**
 * recap_update module 的解析结果。
 */
public record RecapUpdateResult(String recapText) implements ReflectionModuleResult {

    public RecapUpdateResult {
        recapText = recapText == null ? "" : recapText;
    }
}
