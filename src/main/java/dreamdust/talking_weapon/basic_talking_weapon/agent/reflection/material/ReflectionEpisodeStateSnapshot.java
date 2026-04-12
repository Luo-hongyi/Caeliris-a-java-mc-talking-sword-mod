package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material;

/**
 * episode 封口时冻结的低频状态。
 */
public record ReflectionEpisodeStateSnapshot(
        String playerProfile,
        String recap) {

    private static final ReflectionEpisodeStateSnapshot EMPTY =
            new ReflectionEpisodeStateSnapshot("", "");

    public ReflectionEpisodeStateSnapshot {
        playerProfile = playerProfile == null ? "" : playerProfile.trim();
        recap = recap == null ? "" : recap.trim();
    }

    public static ReflectionEpisodeStateSnapshot empty() {
        return EMPTY;
    }
}
