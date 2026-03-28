package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

/**
 * current_situation_update module 的解析结果。
 */
public record CurrentSituationUpdateResult(String summaryText) implements ReflectionModuleResult {

    public CurrentSituationUpdateResult {
        summaryText = summaryText == null ? "" : summaryText;
    }
}
