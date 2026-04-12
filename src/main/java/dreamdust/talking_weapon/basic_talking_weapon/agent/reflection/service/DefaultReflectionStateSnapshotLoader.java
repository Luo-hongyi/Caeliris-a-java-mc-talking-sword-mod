package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionHistoricalStateSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionStateRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionStateSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

import java.util.Objects;

/**
 * 第一版 state snapshot loader。
 */
public final class DefaultReflectionStateSnapshotLoader implements ReflectionStateSnapshotLoader {

    @Override
    public ReflectionStateSnapshot load(
            ReflectionStateRequest request,
            AgentMemory memory,
            AgentState state,
            AgentPersona persona,
            ReflectionHistoricalStateSnapshot historicalStateSnapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(persona, "persona");

        PromptRegistry promptRegistry = PromptRegistry.getInstance();
        ReflectionHistoricalStateSnapshot historical = historicalStateSnapshot == null
                ? ReflectionHistoricalStateSnapshot.empty()
                : historicalStateSnapshot;
        return new ReflectionStateSnapshot(
                request.includeIdentity() ? memory.getIdentity() : "",
                request.includeRoleBackground() ? promptRegistry.getRoleBackground() : "",
                request.includeRolePersonality() ? promptRegistry.getRolePersonality() : "",
                request.includeEmotion()
                        ? firstNonBlank(historical.primaryEmotion(), state.getPrimaryEmotion().name())
                        : "",
                request.includeEmotion()
                        ? firstNonNull(historical.emotionIntensity(), state.getEmotionIntensity())
                        : null,
                request.includeFamiliarity() ? state.getFamiliarity() : null,
                request.includePlayerAddress() ? state.getPlayerAddress() : "",
                request.includeCurrentDesire() ? state.getDesireDescription() : "",
                request.includeFavorability()
                        ? firstNonNull(historical.favorability(), persona.getFavorability())
                        : null,
                request.includePlayerProfile()
                        ? firstNonNullText(historical.playerProfile(), persona.getPlayerProfile())
                        : "",
                request.includeRecap()
                        ? firstNonNullText(historical.recap(), state.getRecap())
                        : "");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static String firstNonNullText(String preferred, String fallback) {
        if (preferred != null) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
