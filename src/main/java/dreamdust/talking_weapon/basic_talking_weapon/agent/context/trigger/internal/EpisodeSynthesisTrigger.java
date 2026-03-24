package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger.internal;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeSynthesisTriggerSignal;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.AgentTask;
import dreamdust.talking_weapon.basic_talking_weapon.agent.task.EpisodeSynthesisTask;

/**
 * 由 episode 子系统发出的内部 synthesis trigger。
 * <p>
 * 它本身不判断“该不该生成记忆”，只消费 episode 已经筛好的 signal，
 * 并把它显式包装成后台任务。
 */
public final class EpisodeSynthesisTrigger implements IInternalAgentTrigger {

    public static final String SOURCE_NAME = "episode_synthesis";

    private final EpisodeSynthesisTriggerSignal signal;

    public EpisodeSynthesisTrigger(EpisodeSynthesisTriggerSignal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        this.signal = signal;
    }

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public AgentTask createTask() {
        return new EpisodeSynthesisTask(signal);
    }

    public EpisodeSynthesisTriggerSignal getSignal() {
        return signal;
    }
}
