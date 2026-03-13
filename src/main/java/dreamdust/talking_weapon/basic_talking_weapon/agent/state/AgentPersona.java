package dreamdust.talking_weapon.basic_talking_weapon.agent.state;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 低频人格画像（Persona）：描述武器灵魂深层的、长期演化的性格特征与关系状态。
 * <p>
 * 与 {@link AgentState}（高频运行时状态）相对，此类的数据变化缓慢，
 * 仅在后台反思任务（ReflectionTask）中由 LLM 更新。
 *
 * <h3>包含内容</h3>
 * <ul>
 * <li><b>好感度（Favorability）</b>：剑对持有者的整体情感偏向，-100 厌恶 ~ +100 深情。</li>
 * <li><b>性格词条（Traits）</b>：与 {@link FocusTag} 绑定的关键词描述，按场景筛选注入 Prompt。</li>
 * <li><b>玩家画像（Player Profile）</b>：剑对持有者的主观动态评价，一段叙事文字。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 此对象的写操作集中在后台 worker 线程（ReflectionTask），
 * 读操作在前台 worker 线程（LLMInteractionTask）。
 * 字段使用 volatile 保证可见性；写入采用整体替换列表的方式避免并发修改问题。
 */
public class AgentPersona {

    /** 好感度：-100（极度厌恶）~ +100（深情厚谊） */
    private volatile int favorability;

    /** 性格与偏好词条列表（由 LLM 在反思中增删） */
    private volatile List<TraitEntry> traits;

    /** 对持有者的动态评价（由 LLM 在反思中重写） */
    private volatile String playerProfile;

    public AgentPersona() {
        this.favorability = 0;
        this.traits = new ArrayList<>();
        this.playerProfile = "";
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

    // ── Traits ───────────────────────────────────────────────────

    public List<TraitEntry> getTraits() {
        return Collections.unmodifiableList(traits);
    }

    /**
     * 原子替换全部性格词条（反思任务写入时使用）。
     */
    public void setTraits(List<TraitEntry> newTraits) {
        this.traits = new ArrayList<>(newTraits);
    }

    /**
     * 追加一条性格词条。
     */
    public void addTrait(TraitEntry entry) {
        List<TraitEntry> copy = new ArrayList<>(traits);
        copy.add(entry);
        this.traits = copy;
    }

    /**
     * 移除指定词条对象（按引用匹配）。
     */
    public void removeTrait(TraitEntry entry) {
        List<TraitEntry> copy = new ArrayList<>(traits);
        copy.remove(entry);
        this.traits = copy;
    }

    /**
     * 返回指定 Tag 下的所有词条（可直接修改，供 GUI 编辑）。
     */
    public List<TraitEntry> getTraitsByTag(FocusTag tag) {
        List<TraitEntry> result = new ArrayList<>();
        for (TraitEntry t : traits) {
            if (tag == t.getAssociatedTag())
                result.add(t);
        }
        return result;
    }

    /**
     * 根据当前上下文激活的 FocusTag 集合，筛选出相关词条。
     * <p>
     * 用于构建 system prompt 时，只注入与当前场景相关的性格描述，
     * 避免无关词条干扰 LLM 输出并减少 Token 消耗。
     *
     * @param activeTags 当前上下文的 FocusTag 集合（来自 TaggedContextEntry）
     * @return 匹配的词条列表；若 activeTags 为空则返回全部词条
     */
    public List<TraitEntry> getTraitsForTags(Set<FocusTag> activeTags) {
        if (activeTags == null || activeTags.isEmpty()) {
            return Collections.unmodifiableList(traits);
        }
        return traits.stream()
                .filter(t -> t.getAssociatedTag() != null && activeTags.contains(t.getAssociatedTag()))
                .collect(Collectors.toList());
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
     * 仅将与 {@code activeTags} 相关的性格词条注入，其余省略。
     * 若 activeTags 为 null，则注入全部词条。
     *
     * @param activeTags 当前帧激活的 FocusTag 集合（可为 null）
     * @return 格式化的 Persona 描述字符串
     */
    public String toPromptSnippet(Set<FocusTag> activeTags) {
        StringBuilder sb = new StringBuilder();

        sb.append("[AgentPersona]\n");
        sb.append("好感度: ").append(favorability).append("/100\n");

        List<TraitEntry> relevantTraits = getTraitsForTags(activeTags);
        if (!relevantTraits.isEmpty()) {
            sb.append("性格特征 (与当前场景相关):\n");
            for (TraitEntry t : relevantTraits) {
                sb.append("  - ").append(t.getTrait()).append("\n");
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
                + ", traits=" + traits.size()
                + ", playerProfile=" + (playerProfile.length() > 20
                        ? playerProfile.substring(0, 20) + "..."
                        : playerProfile)
                + "}";
    }
}
