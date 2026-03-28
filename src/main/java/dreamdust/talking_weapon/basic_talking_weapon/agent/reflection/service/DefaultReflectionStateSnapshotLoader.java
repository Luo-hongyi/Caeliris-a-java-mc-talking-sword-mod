package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.PromptRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
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
            AgentPersona persona) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(persona, "persona");

        PromptRegistry promptRegistry = PromptRegistry.getInstance();
        return new ReflectionStateSnapshot(
                request.includeIdentity() ? memory.getIdentity() : "",
                request.includeRoleBackground() ? promptRegistry.getRoleBackground() : "",
                request.includeRolePersonality() ? promptRegistry.getRolePersonality() : "",
                request.includeEmotion() ? state.getPrimaryEmotion().name() : "",
                request.includeEmotion() ? state.getEmotionIntensity() : null,
                request.includeFamiliarity() ? state.getFamiliarity() : null,
                request.includePlayerAddress() ? state.getPlayerAddress() : "",
                request.includeCurrentDesire() && state.getCurrentDesireTag() != null
                        ? state.getCurrentDesireTag().name()
                        : "",
                request.includeCurrentDesire() ? state.getDesireDescription() : "",
                request.includeFavorability() ? persona.getFavorability() : null,
                request.includePlayerProfile() ? persona.getPlayerProfile() : "",
                // TODO: currentSituationSummary 还没有接到 runtime owner，第一版先留空。
                request.includeCurrentSituationSummary() ? "" : "");
    }
}
