package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

/**
 * emotion_update module 的解析结果。
 */
public record EmotionUpdateResult(
        String emotion,
        int intensity,
        String reason) implements ReflectionModuleResult {

    public EmotionUpdateResult {
        emotion = emotion == null ? "" : emotion;
        reason = reason == null ? "" : reason;
    }
}
