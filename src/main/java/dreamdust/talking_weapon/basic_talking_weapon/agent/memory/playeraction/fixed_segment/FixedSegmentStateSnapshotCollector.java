package dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction;

import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

/**
 * fixed segment 真正封口时采集的高频状态。
 */
public final class FixedSegmentStateSnapshotCollector {

    private FixedSegmentStateSnapshotCollector() {
    }

    public static FixedSegmentStateSnapshot captureCurrent() {
        try {
            AgentRuntime runtime = AgentRuntime.getInstance();
            AgentState state = runtime.getState();
            AgentPersona persona = runtime.getPersona();
            if (state == null || persona == null) {
                return FixedSegmentStateSnapshot.empty();
            }
            return new FixedSegmentStateSnapshot(
                    state.getPrimaryEmotion() == null ? "" : state.getPrimaryEmotion().name(),
                    state.getEmotionIntensity(),
                    persona.getFavorability());
        } catch (Throwable ignored) {
            return FixedSegmentStateSnapshot.empty();
        }
    }
}
