package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 注意力过滤器 (Attention Filter)
 * <p>
 * 负责将客观事件（BaseImportance）结合剑的主观偏好与专注度，转化为最终的主观得分 (FinalScore)。
 * 并提供过滤噪音与取 Top-N 的截断方法，模拟有限的工作记忆。
 */
public class AttentionFilter {

    /**
     * 将 0.0 ~ 10.0 的 FocusTag 权重映射为主观乘数 (默认 5.0 -> 1.0x)。
     * 映射范围大致为 0.2x ~ 2.0x。
     *
     * @param weight        FocusTag 的设定权重 (0.0 - 10.0)
     * @param concentration 剑当前的专注度 (0.0 - 1.0)，0表示雨露均沾，1表示极端偏执
     * @return 最终的乘数
     */
    public static float getSubjectiveMultiplier(double weight, float concentration) {
        // 将 0-10 映射到 0.2 - 2.0 (5.0为中心点 1.1，调整下公式)
        // 更准确的线性映射：weight / 5.0 可得 0.0 ~ 2.0
        // 为防止 weight 极低时分为0被完全抹杀，加上保底 0.2
        float scale = 0.2f + (float) (weight / 10.0) * 1.8f;

        // 根据 focusConcentration 压平或锐化曲线
        // 当 concentration = 0 时，结果逼近 1.0
        // 当 concentration = 1 时，结果应用全部 scale
        return 1.0f + (scale - 1.0f) * Math.max(0f, Math.min(1f, concentration));
    }

    /**
     * 计算单条信息的最终注意力度 (FinalScore)。
     * 取该条信息涵盖的所有 FocusTag 中权重最高的那一个进行计算。
     */
    public static float calculateFinalScore(TaggedContextEntry entry, float concentration) {
        if (entry.getFocusTags().isEmpty()) {
            // 如果没有明确 FocusTag，默认为标准关注度 (multiplier = 1.0)
            return entry.getBaseImportance() * 1.0f;
        }

        double maxWeight = 0.0;
        for (FocusTag tag : entry.getFocusTags()) {
            double w = getFocusWeight(tag);
            if (w > maxWeight) {
                maxWeight = w;
            }
        }

        float multiplier = getSubjectiveMultiplier(maxWeight, concentration);
        return entry.getBaseImportance() * multiplier;
    }

    /**
     * 过滤并截断环境信息。
     *
     * @param inputs         原始的采集器信息列表
     * @param maxCount       最大允许吞吐量 (Top-N，如 7)
     * @param noiseThreshold 潜意识噪音门槛 (低于此值的信息将被直接抛弃，如 0.2)
     * @param concentration  当前的专注度
     * @return 过滤后按得分降序排列的 Top-N 列表
     */
    public static List<TaggedContextEntry> filterAndTopN(List<TaggedContextEntry> inputs, int maxCount, float noiseThreshold, float concentration) {
        // 临时包装类，用于排序
        class ScoredEntry {
            final TaggedContextEntry entry;
            final float score;

            ScoredEntry(TaggedContextEntry entry, float score) {
                this.entry = entry;
                this.score = score;
            }
        }

        List<ScoredEntry> scoredList = new ArrayList<>();
        for (TaggedContextEntry entry : inputs) {
            float score = calculateFinalScore(entry, concentration);
            if (score >= noiseThreshold) {
                scoredList.add(new ScoredEntry(entry, score));
            }
        }

        // 按 score 降序排
        scoredList.sort((a, b) -> Float.compare(b.score, a.score));

        // 截断取 Top-N
        int limit = Math.min(maxCount, scoredList.size());
        List<TaggedContextEntry> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            results.add(scoredList.get(i).entry);
        }

        return results;
    }

    /**
     * 根据配置文件读取对应 FocusTag 的权重。
     */
    private static double getFocusWeight(FocusTag tag) {
        try {
            switch (tag) {
                case COMBAT: return TalkingWeaponConfig.WEIGHT_COMBAT.get();
                case MOVEMENT: return TalkingWeaponConfig.WEIGHT_MOVEMENT.get();
                case LABOR: return TalkingWeaponConfig.WEIGHT_LABOR.get();
                case CREATION: return TalkingWeaponConfig.WEIGHT_CREATION.get();
                case SOCIAL: return TalkingWeaponConfig.WEIGHT_SOCIAL.get();
                case LANDSCAPE: return TalkingWeaponConfig.WEIGHT_LANDSCAPE.get();
                case HAZARD: return TalkingWeaponConfig.WEIGHT_HAZARD.get();
                case STATUS: return TalkingWeaponConfig.WEIGHT_STATUS.get();
                case CONSUMABLE: return TalkingWeaponConfig.WEIGHT_CONSUMABLE.get();
                case BLOCK: return TalkingWeaponConfig.WEIGHT_BLOCK.get();
                case TOOL: return TalkingWeaponConfig.WEIGHT_TOOL.get();
                case COMMON: return 5.0; // COMMON 标签是不参与过滤偏好的固有标签，默认给5.0基准分数
                default: return 5.0;
            }
        } catch (Exception e) {
            return 5.0;
        }
    }
}
