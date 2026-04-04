package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

/**
 * Reflection module 的稳定身份。
 */
public enum ReflectionModuleKey {
    MEMORY_SYNTHESIS("memory_synthesis"),
    PLAYER_PROFILE_UPDATE("player_profile_update"),
    RECAP_UPDATE("recap_update"),
    FAVORABILITY_UPDATE("favorability_update"),
    EMOTION_UPDATE("emotion_update");

    private final String wireName;

    ReflectionModuleKey(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
