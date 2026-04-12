package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

/**
 * segment 封口时挂载的高频状态快照。
 * <p>
 * 当前只冻结容易因 reflection 延迟而失真的高频槽位：
 * 情绪与好感度。
 */
public record FixedSegmentStateSnapshot(
        String primaryEmotion,
        Integer emotionIntensity,
        Integer favorability) {

    private static final FixedSegmentStateSnapshot EMPTY =
            new FixedSegmentStateSnapshot("", null, null);

    public FixedSegmentStateSnapshot {
        primaryEmotion = primaryEmotion == null ? "" : primaryEmotion.trim();
    }

    public static FixedSegmentStateSnapshot empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return primaryEmotion.isBlank()
                && emotionIntensity == null
                && favorability == null;
    }
}
