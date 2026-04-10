package dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag;

/**
 * TaggedContext 条目的主体来源类型。
 * <p>
 * 语义上表示“这条事实属于谁 / 由谁发起”，
 * 而不是“这条事实是通过哪个采集器观察到的”。
 */
public enum TaggedContextSourceType {
    PLAYER,
    AGENT,
    ENVIRONMENT
}
