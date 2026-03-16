package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.*;

/**
 * 潜意识直通器 (Subconscious Booster)。
 * <p>
 * 从全量环境 Context 中概率抽取 0~2 条"潜意识注意到"的条目，
 * 这些条目将直接绕过 AttentionFilter 的 Top-K 截断，强制进入 visibleContexts。
 * <p>
 * 抽取规则：
 * <ul>
 *   <li>抽几条：由 concentration 决定的二项分布（专注时大概率 0 条，散漫时大概率 2 条）</li>
 *   <li>抽哪条：concentration 越高 → 按 FocusTag 权重加权；concentration 越低 → 趋于均匀随机</li>
 * </ul>
 */
public class SubconsciousBooster {

    private static final Random RNG = new Random();

    /**
     * 从全量 Context 中概率抽取直通条目。
     *
     * @param allContexts   全量环境 Context
     * @param concentration 当前专注度 (0.0 散漫 ~ 1.0 专注)
     * @return 被潜意识选中的 0~2 条直通条目
     */
    public static List<TaggedContextEntry> pick(List<TaggedContextEntry> allContexts, float concentration) {
        if (allContexts == null || allContexts.isEmpty()) {
            return Collections.emptyList();
        }

        // ── 决定抽几条 ──
        float r = 1.0f - concentration; // randomness
        // 二项分布概率：P(0)=c², P(1)=2cr, P(2)=r²
        double roll = RNG.nextDouble();
        int count;
        double p0 = concentration * concentration;
        double p1 = 2.0 * concentration * r;
        if (roll < p0) {
            count = 0;
        } else if (roll < p0 + p1) {
            count = 1;
        } else {
            count = 2;
        }

        if (count == 0) {
            return Collections.emptyList();
        }

        // ── 计算每条 Context 的抽取权重 ──
        // P(ctx) = concentration × P_focus(ctx) + randomness × P_uniform(ctx)
        double[] weights = new double[allContexts.size()];
        double totalWeight = 0.0;
        double uniformWeight = 1.0 / allContexts.size();

        for (int i = 0; i < allContexts.size(); i++) {
            TaggedContextEntry ctx = allContexts.get(i);
            double focusWeight = getFocusWeight(ctx);
            weights[i] = concentration * focusWeight + r * uniformWeight;
            totalWeight += weights[i];
        }

        // ── 按权重不放回抽取 ──
        List<TaggedContextEntry> picked = new ArrayList<>();
        boolean[] used = new boolean[allContexts.size()];

        for (int n = 0; n < count && n < allContexts.size(); n++) {
            double target = RNG.nextDouble() * totalWeight;
            double cumulative = 0.0;
            for (int i = 0; i < allContexts.size(); i++) {
                if (used[i]) continue;
                cumulative += weights[i];
                if (cumulative >= target) {
                    picked.add(allContexts.get(i));
                    used[i] = true;
                    totalWeight -= weights[i];
                    break;
                }
            }
        }

        return picked;
    }

    /**
     * 计算单条 Context 的 FocusTag 权重（归一化后用于加权抽取）。
     * 取该条目所有 FocusTag 中配置权重最高的那个。
     */
    private static double getFocusWeight(TaggedContextEntry entry) {
        if (entry.getFocusTags().isEmpty()) {
            return 0.5; // 无标签的默认中位权重
        }
        double maxWeight = 0.0;
        for (FocusTag tag : entry.getFocusTags()) {
            double w = getConfigWeight(tag);
            if (w > maxWeight) maxWeight = w;
        }
        // 归一化到 0~1 范围（配置权重为 0~10）
        return maxWeight / 10.0;
    }

    private static double getConfigWeight(FocusTag tag) {
        try {
            switch (tag) {
                case COMBAT: return TalkingWeaponConfig.WEIGHT_COMBAT.get();
                case MOVEMENT: return TalkingWeaponConfig.WEIGHT_MOVEMENT.get();
                case LABOR: return TalkingWeaponConfig.WEIGHT_LABOR.get();
                case CREATION: return TalkingWeaponConfig.WEIGHT_CREATION.get();
                case CREATURE: return TalkingWeaponConfig.WEIGHT_CREATURE.get();
                case SOCIAL: return TalkingWeaponConfig.WEIGHT_SOCIAL.get();
                case LANDSCAPE: return TalkingWeaponConfig.WEIGHT_LANDSCAPE.get();
                case HAZARD: return TalkingWeaponConfig.WEIGHT_HAZARD.get();
                case STATUS: return TalkingWeaponConfig.WEIGHT_STATUS.get();
                case CONSUMABLE: return TalkingWeaponConfig.WEIGHT_CONSUMABLE.get();
                case BLOCK: return TalkingWeaponConfig.WEIGHT_BLOCK.get();
                case TOOL: return TalkingWeaponConfig.WEIGHT_TOOL.get();
                case COMMON: return 5.0;
                default: return 5.0;
            }
        } catch (Exception e) {
            return 5.0;
        }
    }
}
