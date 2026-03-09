package dreamdust.talking_weapon.basic_talking_weapon.agent.action;

/**
 * 表情/粒子动作：在武器或玩家周围产生粒子效果。
 * <p>
 * 用于视觉化武器灵魂的情绪状态，如开心时冒爱心粒子，
 * 愤怒时冒火焰粒子等。
 */
public class EmoteAction extends AgentAction {

    /** 表情类型 */
    private final EmoteType emoteType;

    /** 粒子持续时间 (毫秒) */
    private final long durationMs;

    public EmoteAction(EmoteType emoteType, long durationMs) {
        super("emote");
        this.emoteType = emoteType;
        this.durationMs = durationMs;
    }

    public EmoteAction(EmoteType emoteType) {
        this(emoteType, 2000L);
    }

    @Override
    public void perform() {
        // TODO: 实现粒子效果
        // 通过 Minecraft.getInstance().tell() 提交到主线程
        // 根据 emoteType 选择合适的 ParticleTypes 并 addParticle()
    }

    @Override
    public long estimatedDurationMs() {
        return durationMs;
    }

    // ---- Getters ----

    public EmoteType getEmoteType() { return emoteType; }
    public long getDurationMs() { return durationMs; }

    @Override
    public String toString() {
        return "EmoteAction[" + emoteType + ", " + durationMs + "ms]";
    }

    // ---- Emote Types ----

    public enum EmoteType {
        /** 爱心粒子 */
        HAPPY,
        /** 火焰粒子 */
        ANGRY,
        /** 星星粒子 */
        EXCITED,
        /** 烟雾粒子 */
        BORED,
        /** 雪花粒子 */
        SCARED,
        /** 水滴粒子 */
        SAD,
        /** 附魔粒子 */
        CURIOUS
    }
}
