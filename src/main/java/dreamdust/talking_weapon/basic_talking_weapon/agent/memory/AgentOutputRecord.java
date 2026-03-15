package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

/**
 * Agent 自身产出的不可变记录。
 * <p>
 * 与 {@link ActionRecord}（记录玩家动作）不同，此类记录的是剑灵自身的
 * 输出行为：说出的话或内心碎碎念。存入 {@link STMPool#agentActions} 中，
 * 与玩家/环境的 {@code TaggedContextEntry} 分区存储。
 */
public final class AgentOutputRecord {

    public enum Type {
        /** 剑灵说出的话（玩家可见） */
        SPEECH,
        /** 剑灵的内心碎碎念（无 speech 产出时的静默思考） */
        INNER_MONOLOGUE
    }

    private final Type type;
    private final String text;
    private final long timestamp;

    public AgentOutputRecord(Type type, String text) {
        this.type = type;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }

    // ── Getters ──

    public Type getType() { return type; }
    public String getText() { return text; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        String preview = text.length() > 40 ? text.substring(0, 40) + "..." : text;
        return "AgentOutput[" + type + ": \"" + preview + "\"]";
    }
}
