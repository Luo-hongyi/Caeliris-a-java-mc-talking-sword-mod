package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagRelationDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.AgentPersona;
import dreamdust.talking_weapon.basic_talking_weapon.agent.state.TraitEntry;

import java.util.*;

/**
 * 表层意识偏好匹配器 (Persona Matcher)。
 * <p>
 * 在 visibleContexts 确定之后，对每一条可见的环境事物做确定性全量精准匹配，
 * 绝不遗漏任何一个 LLM 能看到的偏好关联。
 * <p>
 * 高级匹配规则：
 * <ul>
 *   <li><b>防穿透交集算法</b>：区分 Specific (单点特指) 和 Generic (抽象泛指) 标签。</li>
 *   <li>如果环境与偏好命中了任何 Specific 标签 -> 绝对匹配。</li>
 *   <li>如果交集中只有 Generic 标签 -> 检查偏好是否含有 Specific 约束。如果没有（纯泛指偏好），则匹配；如果有（受到特指约束但未能满足），则拒绝。</li>
 * </ul>
 */
public class PersonaMatcher {

    public static class MatchResult {
        /** 被激发的性格词条 */
        public final List<TraitEntry> activeTraits;
        /** 哪些环境 DetailTag 触发了联想（用于后续 associativeOnly 过滤判定） */
        public final Set<String> triggeringDetailTags;
        /** 每条上下文分别是如何触发人格词条的 */
        public final List<TriggeredTrait> triggeredTraits;

        public MatchResult(List<TraitEntry> activeTraits, Set<String> triggeringDetailTags,
                List<TriggeredTrait> triggeredTraits) {
            this.activeTraits = activeTraits;
            this.triggeringDetailTags = triggeringDetailTags;
            this.triggeredTraits = triggeredTraits;
        }
    }

    public static class TriggeredTrait {
        public final TraitEntry trait;
        public final TaggedContextEntry contextEntry;
        public final List<String> matchedContextTags;
        public final List<String> matchedTraitTags;
        public final String matchMode;

        public TriggeredTrait(TraitEntry trait, TaggedContextEntry contextEntry,
                List<String> matchedContextTags, List<String> matchedTraitTags, String matchMode) {
            this.trait = trait;
            this.contextEntry = contextEntry;
            this.matchedContextTags = Collections.unmodifiableList(new ArrayList<>(matchedContextTags));
            this.matchedTraitTags = Collections.unmodifiableList(new ArrayList<>(matchedTraitTags));
            this.matchMode = matchMode;
        }
    }

    /**
     * 确定性全量匹配。
     *
     * @param visibleContexts 已经确定会被 LLM 看见的环境条目
     * @param persona         Agent 人格设定
     * @return 匹配结果
     */
    public static MatchResult match(List<TaggedContextEntry> visibleContexts, AgentPersona persona) {
        if (visibleContexts == null || visibleContexts.isEmpty() || persona.getTraits().isEmpty()) {
            return new MatchResult(Collections.emptyList(), Collections.emptySet(), Collections.emptyList());
        }

        Set<TraitEntry> activeTraits = new LinkedHashSet<>();
        Set<String> triggeringDetailTags = new LinkedHashSet<>();
        List<TriggeredTrait> triggeredTraits = new ArrayList<>();
        TagRelationDictionary relationDict = TagRelationDictionary.getInstance();

        for (TraitEntry trait : persona.getTraits()) {
            boolean requiresExactDetail = !trait.getDetailTags().isEmpty();
            boolean traitTriggered = false;
            List<DetailTag> traitTags = new ArrayList<>();
            boolean hasTraitSpecifics = false;

            if (requiresExactDetail) {
                for (String reqStr : trait.getDetailTags()) {
                    DetailTag dt = DetailTag.parse(reqStr);
                    traitTags.add(dt);
                    if (dt.isSpecific()) {
                        hasTraitSpecifics = true;
                    }
                }
            }

            for (TaggedContextEntry source : visibleContexts) {
                if (requiresExactDetail) {
                    boolean sharesSpecific = false;
                    boolean sharesGeneric = false;
                    List<String> matchedEnvRawTags = new ArrayList<>();
                    List<String> matchedTraitRawTags = new ArrayList<>();

                    // 探索环境与偏好的交集
                    for (DetailTag tTag : traitTags) {
                        String tTagStr = tTag.toString();
                        Set<String> traitClosure = relationDict.expand(tTagStr);
                        for (DetailTag envDetail : source.getDetailTags()) {
                            String rawEnv = envDetail.toString();
                            Set<String> envClosure = relationDict.expand(rawEnv);
                            Set<String> overlap = new LinkedHashSet<>(traitClosure);
                            overlap.retainAll(envClosure);
                            if (!overlap.isEmpty()) {
                                if (tTag.isSpecific()) sharesSpecific = true;
                                else sharesGeneric = true;
                                matchedEnvRawTags.add(rawEnv);
                                matchedTraitRawTags.add(tTagStr);
                            }
                        }
                    }

                    // 防穿透判别
                    String matchMode = null;
                    if (sharesSpecific) {
                        traitTriggered = true;
                        matchMode = "detail_specific";
                    } else if (sharesGeneric && !hasTraitSpecifics) {
                        traitTriggered = true;
                        matchMode = "detail_generic";
                    }

                    if (matchMode != null) {
                        triggeringDetailTags.addAll(matchedEnvRawTags);
                        triggeredTraits.add(new TriggeredTrait(
                                trait,
                                source,
                                matchedEnvRawTags,
                                matchedTraitRawTags,
                                matchMode));
                    }
                } else if (trait.getAssociatedTag() != null) {
                    // 无 Detail 约束的泛用偏好：FocusTag 命中即可
                    if (source.hasFocus(trait.getAssociatedTag())) {
                        traitTriggered = true;
                        triggeredTraits.add(new TriggeredTrait(
                                trait,
                                source,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                "focus"));
                    }
                }
            }

            if (traitTriggered) {
                activeTraits.add(trait);
            }
        }

        return new MatchResult(new ArrayList<>(activeTraits), triggeringDetailTags, triggeredTraits);
    }
}
