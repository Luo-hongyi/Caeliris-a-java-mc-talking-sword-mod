package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 低频人格画像（Persona）：描述武器灵魂深层的、长期演化的性格特征与关系状态。
 * <p>
 * 与 {@link AgentState}（高频运行时状态）相对，此类的数据变化缓慢，
 * 由低频后台链路更新；当前应由新的 reflection / episode synthesis 设计承接。
 *
 * <h3>包含内容</h3>
 * <ul>
 * <li><b>好感度（Favorability）</b>：剑对持有者的整体情感偏向，-100 厌恶 ~ +100 深情。</li>
 * <li><b>人格条目（Persona Entries）</b>：与认知领域绑定的关键词描述，按场景筛选注入 Prompt。</li>
 * <li><b>玩家画像（Player Profile）</b>：剑对持有者的主观动态评价，一段叙事文字。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 此对象的写操作集中在后台 worker 线程，
 * 读操作在前台 worker 线程（LLMInteractionTask）。
 * 字段使用 volatile 保证可见性；写入采用整体替换列表的方式避免并发修改问题。
 */
public class AgentPersona {

    /** 好感度：-100（极度厌恶）~ +100（深情厚谊） */
    private volatile int favorability;

    /** 静态 persona 条目列表（当前从 personas.json 加载） */
    private volatile List<PersonaEntry> personaEntries;

    /** 对持有者的动态评价（由 LLM 在反思中重写） */
    private volatile String playerProfile;

    public AgentPersona() {
        this.favorability = 0;
        this.personaEntries = new ArrayList<>();
        this.playerProfile = "";
        reloadStudioPersonaEntries();
    }

    // ── Favorability ─────────────────────────────────────────────

    public int getFavorability() {
        return favorability;
    }

    /**
     * 更新好感度，自动 clamped 至 [-100, 100]。
     */
    public void setFavorability(int favorability) {
        this.favorability = Math.max(-100, Math.min(100, favorability));
    }

    /**
     * 对好感度施加增量（可为负数），自动 clamped。
     */
    public void adjustFavorability(int delta) {
        setFavorability(this.favorability + delta);
    }

    // ── Persona Entries ──────────────────────────────────────────

    public List<PersonaEntry> getPersonaEntries() {
        return Collections.unmodifiableList(personaEntries);
    }

    /**
     * 原子替换全部 persona 条目。
     */
    public void setPersonaEntries(List<PersonaEntry> newEntries) {
        this.personaEntries = new ArrayList<>(newEntries);
    }

    /**
     * 追加一条 persona 条目，并立即持久化到 personas.json。
     */
    public void addPersonaEntry(PersonaEntry entry) {
        List<PersonaEntry> copy = new ArrayList<>(personaEntries);
        copy.add(entry);
        this.personaEntries = copy;
        PersonaStorage.saveEntries(copy);
    }

    /**
     * 移除指定 persona 条目，并立即持久化到 personas.json。
     */
    public void removePersonaEntry(PersonaEntry entry) {
        List<PersonaEntry> copy = new ArrayList<>(personaEntries);
        copy.remove(entry);
        this.personaEntries = copy;
        PersonaStorage.saveEntries(copy);
    }

    /**
     * 返回包含指定 Focus 的所有 persona 条目。
     * 多 focus persona 会同时出现在多个 focus 分组中。
     */
    public List<PersonaEntry> getPersonaEntriesByFocus(dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag tag) {
        List<PersonaEntry> result = new ArrayList<>();
        for (PersonaEntry t : personaEntries) {
            if (t.hasFocus(tag))
                result.add(t);
        }
        return result;
    }

    public void reloadStudioPersonaEntries() {
        setPersonaEntries(PersonaStorage.loadRuntimePersonas());
    }

    // ── Player Profile ────────────────────────────────────────────

    public String getPlayerProfile() {
        return playerProfile;
    }

    public void setPlayerProfile(String playerProfile) {
        this.playerProfile = playerProfile != null ? playerProfile : "";
    }

    // ── Prompt 注入 ───────────────────────────────────────────────

    /**
     * 生成用于注入 system prompt 的 Persona 片段。
     * <p>
     * 仅将传入的 persona 条目注入，其余省略。
     *
     * @param relevantEntries 外部筛选好的、当前场景激活的条目列表
     * @return 格式化的 Persona 描述字符串
     */
    public String toPromptSnippet(List<PersonaEntry> relevantEntries) {
        StringBuilder sb = new StringBuilder();

        sb.append("[AgentPersona]\n");
        sb.append("好感度: ").append(favorability).append("/100\n");

        if (relevantEntries != null && !relevantEntries.isEmpty()) {
            sb.append("人格偏好 (与当前场景相关):\n");
            for (PersonaEntry entry : relevantEntries) {
                sb.append("  - ").append(entry.getDescription()).append("\n");
            }
        }

        if (playerProfile != null && !playerProfile.isBlank()) {
            sb.append("对持有者的看法: ").append(playerProfile).append("\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "AgentPersona{favorability=" + favorability
                + ", personas=" + personaEntries.size()
                + ", playerProfile=" + (playerProfile.length() > 20
                        ? playerProfile.substring(0, 20) + "..."
                        : playerProfile)
                + "}";
    }
}
