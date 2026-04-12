package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

/**
 * segment 封口时冻结的高频状态。
 */
public record ReflectionSegmentStateSnapshot(
        String primaryEmotion,
        Integer emotionIntensity,
        Integer favorability) {

    private static final ReflectionSegmentStateSnapshot EMPTY =
            new ReflectionSegmentStateSnapshot("", null, null);

    public ReflectionSegmentStateSnapshot {
        primaryEmotion = primaryEmotion == null ? "" : primaryEmotion.trim();
    }

    public static ReflectionSegmentStateSnapshot empty() {
        return EMPTY;
    }
}
