package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

/**
 * ReflectionTask 真正暴露给 LLM 的状态快照。
 * <p>
 * 这是按 {@link ReflectionStateRequest} 裁切后的结果，
 * 字段可以来自 AgentMemory / PromptRegistry / AgentState / AgentPersona。
 */
public record ReflectionStateSnapshot(
        String identity,
        String roleBackground,
        String rolePersonality,
        String primaryEmotion,
        Integer emotionIntensity,
        Integer familiarity,
        String playerAddress,
        String currentDesireTag,
        String currentDesireDescription,
        Integer favorability,
        String playerProfile,
        String currentSituationSummary) {

    public ReflectionStateSnapshot {
        identity = identity == null ? "" : identity;
        roleBackground = roleBackground == null ? "" : roleBackground;
        rolePersonality = rolePersonality == null ? "" : rolePersonality;
        primaryEmotion = primaryEmotion == null ? "" : primaryEmotion;
        playerAddress = playerAddress == null ? "" : playerAddress;
        currentDesireTag = currentDesireTag == null ? "" : currentDesireTag;
        currentDesireDescription = currentDesireDescription == null ? "" : currentDesireDescription;
        playerProfile = playerProfile == null ? "" : playerProfile;
        currentSituationSummary = currentSituationSummary == null ? "" : currentSituationSummary;
    }
}
