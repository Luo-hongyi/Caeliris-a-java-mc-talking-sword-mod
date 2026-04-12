package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

/**
 * episode 封口时挂载的低频状态快照。
 * <p>
 * 当前只冻结 recap 与 player profile，
 * 避免延迟较高的记忆合成任务读取到明显偏后的当前态。
 */
public record EpisodeRegionStateSnapshot(
        String playerProfile,
        String recap) {

    private static final EpisodeRegionStateSnapshot EMPTY =
            new EpisodeRegionStateSnapshot("", "");

    public EpisodeRegionStateSnapshot {
        playerProfile = playerProfile == null ? "" : playerProfile.trim();
        recap = recap == null ? "" : recap.trim();
    }

    public static EpisodeRegionStateSnapshot empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return playerProfile.isBlank() && recap.isBlank();
    }
}
