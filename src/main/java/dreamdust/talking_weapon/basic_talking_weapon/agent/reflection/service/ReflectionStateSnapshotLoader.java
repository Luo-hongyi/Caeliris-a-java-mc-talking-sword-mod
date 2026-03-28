package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionStateRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.task.ReflectionStateSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentState;

/**
 * 按请求裁切 reflection state snapshot。
 */
public interface ReflectionStateSnapshotLoader {

    ReflectionStateSnapshot load(
            ReflectionStateRequest request,
            AgentMemory memory,
            AgentState state,
            AgentPersona persona);
}
