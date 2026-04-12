package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode;

import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

/**
 * region 封口时采集的低频状态。
 */
public final class EpisodeRegionStateSnapshotCollector {

    private EpisodeRegionStateSnapshotCollector() {
    }

    public static EpisodeRegionStateSnapshot captureCurrent() {
        try {
            AgentRuntime runtime = AgentRuntime.getInstance();
            AgentState state = runtime.getState();
            AgentPersona persona = runtime.getPersona();
            if (state == null || persona == null) {
                return EpisodeRegionStateSnapshot.empty();
            }
            return new EpisodeRegionStateSnapshot(
                    persona.getPlayerProfile(),
                    state.getRecap());
        } catch (Throwable ignored) {
            return EpisodeRegionStateSnapshot.empty();
        }
    }
}
