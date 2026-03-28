package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

/**
 * source material cleanup 的执行结果。
 */
public record MemorySourceMaterialCleanupResult(
        MemorySourceMaterialCleanupPlan plan,
        boolean stale,
        int removedRawActionCount,
        int removedEpisodeSegmentCount,
        int removedStmContextCount,
        int removedStmInteractionCount) {

    public MemorySourceMaterialCleanupResult {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        removedRawActionCount = Math.max(0, removedRawActionCount);
        removedEpisodeSegmentCount = Math.max(0, removedEpisodeSegmentCount);
        removedStmContextCount = Math.max(0, removedStmContextCount);
        removedStmInteractionCount = Math.max(0, removedStmInteractionCount);
    }

    public static MemorySourceMaterialCleanupResult stale(MemorySourceMaterialCleanupPlan plan) {
        return new MemorySourceMaterialCleanupResult(plan, true, 0, 0, 0, 0);
    }

    public boolean applied() {
        return !stale
                && (removedRawActionCount > 0
                || removedEpisodeSegmentCount > 0
                || removedStmContextCount > 0
                || removedStmInteractionCount > 0);
    }
}
