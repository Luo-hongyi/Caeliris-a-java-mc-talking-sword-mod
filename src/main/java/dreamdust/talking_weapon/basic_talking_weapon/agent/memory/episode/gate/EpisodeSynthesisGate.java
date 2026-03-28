package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate;

/**
 * Episode closed region 是否值得进入 reflection 的独立 gate。
 * <p>
 * 这里固定只负责判定，不负责 signal 提交，也不负责 owner-side cleanup。
 */
public interface EpisodeSynthesisGate {

    EpisodeSynthesisGateResult evaluate(EpisodeSynthesisGateInput input);
}
