package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.gate;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegionGateDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Episode gate 的正式判定结果。
 */
public record EpisodeSynthesisGateResult(
        EpisodeRegionGateDecision gateDecision,
        List<String> reasons) {

    public EpisodeSynthesisGateResult {
        gateDecision = Objects.requireNonNull(gateDecision, "gateDecision");
        if (gateDecision == EpisodeRegionGateDecision.UNSET) {
            throw new IllegalArgumentException("gateDecision must be PASS or REJECT");
        }
        reasons = reasons == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(reasons));
    }

    public static EpisodeSynthesisGateResult pass(String reason) {
        return new EpisodeSynthesisGateResult(
                EpisodeRegionGateDecision.PASS,
                reason == null || reason.isBlank() ? List.of() : List.of(reason.trim()));
    }

    public static EpisodeSynthesisGateResult reject(String reason) {
        return new EpisodeSynthesisGateResult(
                EpisodeRegionGateDecision.REJECT,
                reason == null || reason.isBlank() ? List.of() : List.of(reason.trim()));
    }
}
