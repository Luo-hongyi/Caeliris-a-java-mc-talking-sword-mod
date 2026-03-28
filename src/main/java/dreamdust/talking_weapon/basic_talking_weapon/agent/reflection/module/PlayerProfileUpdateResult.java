package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.module;

/**
 * player_profile_update module 的解析结果。
 */
public record PlayerProfileUpdateResult(String profileText) implements ReflectionModuleResult {

    public PlayerProfileUpdateResult {
        profileText = profileText == null ? "" : profileText;
    }
}
