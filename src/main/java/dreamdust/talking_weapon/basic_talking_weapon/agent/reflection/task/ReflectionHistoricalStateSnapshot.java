package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

/**
 * task 可选提供的历史状态覆盖层。
 * <p>
 * 当前只覆盖会因 task 延迟而失真的状态槽位。
 */
public record ReflectionHistoricalStateSnapshot(
        String primaryEmotion,
        Integer emotionIntensity,
        Integer favorability,
        String playerProfile,
        String recap) {

    private static final ReflectionHistoricalStateSnapshot EMPTY =
            new ReflectionHistoricalStateSnapshot(null, null, null, null, null);

    public static ReflectionHistoricalStateSnapshot empty() {
        return EMPTY;
    }
}
