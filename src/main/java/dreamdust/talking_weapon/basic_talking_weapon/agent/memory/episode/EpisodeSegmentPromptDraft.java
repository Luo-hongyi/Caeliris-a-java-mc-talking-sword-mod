package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个 fixed segment 对应的人类可读 prompt 草稿。
 * <p>
 * 这是 episode synthesis 的正式中间层：
 * <ul>
 * <li>segment 先各自产出 prompt draft</li>
 * <li>region / episode 再在这层之上继续组织更大的 draft</li>
 * </ul>
 */
public record EpisodeSegmentPromptDraft(
        int segmentIndex,
        long startTimestamp,
        long endTimestamp,
        List<String> timelineLines,
        String promptDraft) {

    public EpisodeSegmentPromptDraft {
        timelineLines = timelineLines == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(timelineLines));
        promptDraft = promptDraft == null ? "" : promptDraft;
    }
}
