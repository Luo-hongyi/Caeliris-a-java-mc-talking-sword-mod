package dreamdust.talking_weapon.basic_talking_weapon.agent.action;

/**
 * 音效动作：播放一个 Minecraft 音效。
 * <p>
 * 可用于武器灵魂的情绪表达，如兴奋时播放叮当声，害怕时播放低鸣。
 */
public class SoundAction extends AgentAction {

    /** Minecraft 音效的资源路径，如 "minecraft:entity.experience_orb.pickup" */
    private final String soundId;

    /** 音量 (0.0 ~ 1.0) */
    private final float volume;

    /** 音调 (0.5 ~ 2.0) */
    private final float pitch;

    public SoundAction(String soundId, float volume, float pitch) {
        super("sound");
        this.soundId = soundId;
        this.volume = volume;
        this.pitch = pitch;
    }

    public SoundAction(String soundId) {
        this(soundId, 1.0f, 1.0f);
    }

    @Override
    public void perform() {
        // TODO: 实现音效播放
        // 通过 Minecraft.getInstance().tell() 提交到主线程
        // 使用 player.playSound() 或 level.playSound()
    }

    @Override
    public long estimatedDurationMs() {
        return 500L;
    }

    // ---- Getters ----

    public String getSoundId() { return soundId; }
    public float getVolume() { return volume; }
    public float getPitch() { return pitch; }

    @Override
    public String toString() {
        return "SoundAction[" + soundId + ", vol=" + volume + ", pitch=" + pitch + "]";
    }
}
