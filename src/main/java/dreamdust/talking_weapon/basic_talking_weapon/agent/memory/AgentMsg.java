package dreamdust.talking_weapon.basic_talking_weapon.agent.memory;

/**
 * Agent 消息：对话历史中的一条记录。
 * 不可变对象，用于构建发送给 LLM 的消息列表以及存储在记忆中。
 */
public final class AgentMsg {

    /** 消息角色 */
    private final Role role;

    /** 文本内容 */
    private final String content;

    /** 图片负载 (Base64 编码，可为 null) */
    private final String imagePayload;

    /** 创建时间戳 (毫秒) */
    private final long createdAt;

    /** 附加元数据 (如触发来源、生物群系等，可为 null) */
    private final String metadata;

    public AgentMsg(Role role, String content, String imagePayload, String metadata) {
        this.role = role;
        this.content = content;
        this.imagePayload = imagePayload;
        this.createdAt = System.currentTimeMillis();
        this.metadata = metadata;
    }

    /** 便捷工厂：纯文本系统消息 */
    public static AgentMsg system(String content) {
        return new AgentMsg(Role.SYSTEM, content, null, null);
    }

    /** 便捷工厂：纯文本用户消息 */
    public static AgentMsg user(String content) {
        return new AgentMsg(Role.USER, content, null, null);
    }

    /** 便捷工厂：带图片的用户消息 */
    public static AgentMsg userWithImage(String content, String imageBase64) {
        return new AgentMsg(Role.USER, content, imageBase64, null);
    }

    /** 便捷工厂：助手回复 */
    public static AgentMsg assistant(String content) {
        return new AgentMsg(Role.ASSISTANT, content, null, null);
    }

    // ---- Getters ----

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getImagePayload() { return imagePayload; }
    public long getCreatedAt() { return createdAt; }
    public String getMetadata() { return metadata; }

    public boolean hasImage() {
        return imagePayload != null && !imagePayload.isEmpty();
    }

    @Override
    public String toString() {
        return "[" + role + "] " + (content.length() > 60 ? content.substring(0, 60) + "..." : content);
    }

    // ---- Role Enum ----

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }
}
