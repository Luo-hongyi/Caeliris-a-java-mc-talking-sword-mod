package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task;

/**
 * Reflection task 的稳定身份。
 */
public enum ReflectionTaskType {
    EPISODE_SYNTHESIS("EpisodeSynthesisReflection", "episode_synthesis");

    private final String displayName;
    private final String wireName;

    ReflectionTaskType(String displayName, String wireName) {
        this.displayName = displayName;
        this.wireName = wireName;
    }

    public String displayName() {
        return displayName;
    }

    public String wireName() {
        return wireName;
    }
}
