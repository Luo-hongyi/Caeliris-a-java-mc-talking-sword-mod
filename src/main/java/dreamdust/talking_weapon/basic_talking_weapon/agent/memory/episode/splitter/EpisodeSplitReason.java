package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.splitter;

/**
 * Episode v2 中的统一 split reason。
 */
public enum EpisodeSplitReason {
    DEATH("death"),
    INTENT_HARD_SHIFT("intent_hard_shift"),
    INTENT_SOFT_SHIFT("intent_soft_shift"),
    FALLBACK_QUIET_GAP("fallback_quiet_gap");

    private final String workspaceKey;

    EpisodeSplitReason(String workspaceKey) {
        this.workspaceKey = workspaceKey;
    }

    public String workspaceKey() {
        return workspaceKey;
    }

    public static EpisodeSplitReason fromWorkspaceKey(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        for (EpisodeSplitReason reason : values()) {
            if (reason.workspaceKey.equals(normalized)) {
                return reason;
            }
        }
        return null;
    }
}
