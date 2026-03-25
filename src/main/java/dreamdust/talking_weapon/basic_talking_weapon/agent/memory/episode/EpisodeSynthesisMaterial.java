package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Episode synthesis 前的正式拼装结果。
 * <p>
 * 它代表“一个已闭合 region + 按时间分配完 STM 信息后的 fixed segments”。
 */
public record EpisodeSynthesisMaterial(
        EpisodeSynthesisTriggerSignal signal,
        EpisodeRegion region,
        List<FixedActionSegment> segments) {

    public EpisodeSynthesisMaterial {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        segments = segments == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public String buildPromptDraft() {
        return EpisodeSynthesisPromptFormatter.buildPromptDraft(this);
    }
}
