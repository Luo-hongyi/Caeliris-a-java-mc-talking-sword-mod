package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.ActionType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MovingActionBuffer;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MovingActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MovingActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.runtime.AgentRuntime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 近期动作采集器。
 * <p>
 * 从 {@link MovingActionBuffer} 读取 prompt 侧的单一 moving prompt window，
 * 并将窗口中的动作条目拆成较细粒度的“刚刚经历了什么”片段注入上下文。
 */
public class RecentActionCollector implements IContextCollector {

    private static final int MAX_PROMPT_ENTRIES = 6;

    @Override
    public String getName() {
        return "recent_actions";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        MovingActionBuffer movingActionBuffer = AgentRuntime.getInstance().getMemory().getMovingActionBuffer();
        if (movingActionBuffer.isEmpty()) {
            return;
        }

        MovingActionSegment promptWindow = movingActionBuffer.snapshotPromptWindowSegment();
        if (promptWindow == null || promptWindow.getEntries().isEmpty()) {
            return;
        }

        List<MovingActionSegmentEntry> promptEntries = promptWindow.getEntries().stream()
                .sorted(Comparator.comparingLong(MovingActionSegmentEntry::getLastTimestamp).reversed())
                .limit(MAX_PROMPT_ENTRIES)
                .sorted(Comparator.comparingLong(MovingActionSegmentEntry::getFirstTimestamp))
                .toList();

        for (int i = 0; i < promptEntries.size(); i++) {
            MovingActionSegmentEntry entry = promptEntries.get(i);
            addPromptEntry(builder, entry, promptEntries.size() - 1 - i);
        }
    }

    private void addPromptEntry(AgentContext.Builder builder, MovingActionSegmentEntry actionEntry, int distanceFromLatest) {
        if (actionEntry == null || actionEntry.getType() == null) {
            return;
        }

        String clause = describeEntry(actionEntry);
        if (clause.isBlank()) {
            return;
        }

        float importance = importanceForEntry(actionEntry, distanceFromLatest);
        String description = buildPromptEntryDescription(actionEntry, clause, distanceFromLatest);
        if (description.isBlank()) {
            return;
        }

        TaggedContextEntry.Builder entryBuilder = TaggedContextEntry.builder()
                .description(description)
                .importance(importance)
                .timestamp(actionEntry.getLastTimestamp());

        for (FocusTag focusTag : actionEntry.getType().getAllFocusTags()) {
            entryBuilder.focus(focusTag);
        }
        for (DetailTag detailTag : actionEntry.getDetailTags()) {
            entryBuilder.detail(detailTag);
        }

        builder.addTaggedEntry(entryBuilder.build());
    }

    private float importanceForEntry(MovingActionSegmentEntry entry, int distanceFromLatest) {
        float best = importanceForFocus(entry.getType().getPrimaryFocus());
        for (FocusTag focusTag : entry.getType().getAllFocusTags()) {
            best = Math.max(best, importanceForFocus(focusTag));
        }
        float countBonus = Math.min(0.18f, Math.max(0, entry.getCount() - 1) * 0.03f);
        float recencyMultiplier = Math.max(0.80f, 1.0f - distanceFromLatest * 0.08f);
        return Math.max(0.0f, Math.min(1.0f, (best + countBonus) * recencyMultiplier));
    }

    private float importanceForFocus(FocusTag focusTag) {
        if (focusTag == null) {
            return ImportanceRegistry.getInstance().get("action:default", 0.3f);
        }
        return switch (focusTag) {
            case COMBAT, HAZARD -> ImportanceRegistry.getInstance().get("action:combat", 0.8f);
            case LABOR, BLOCK, TOOL, CREATION -> ImportanceRegistry.getInstance().get("action:labor", 0.4f);
            case STATUS, CONSUMABLE -> ImportanceRegistry.getInstance().get("action:status", 0.6f);
            case MOVEMENT, LANDSCAPE -> ImportanceRegistry.getInstance().get("action:movement", 0.5f);
            case SOCIAL, CREATURE -> ImportanceRegistry.getInstance().get("action:social", 0.7f);
            default -> ImportanceRegistry.getInstance().get("action:default", 0.3f);
        };
    }

    private String buildPromptEntryDescription(
            MovingActionSegmentEntry entry,
            String clause,
            int distanceFromLatest) {
        String lead = switch (distanceFromLatest) {
            case 0 -> leadForFocus(entry.getType().getPrimaryFocus(), "刚刚玩家");
            case 1 -> leadForFocus(entry.getType().getPrimaryFocus(), "稍早前，玩家");
            default -> leadForFocus(entry.getType().getPrimaryFocus(), "再往前一点，玩家");
        };
        return lead + clause + "。";
    }

    private String leadForFocus(FocusTag primaryFocus, String prefix) {
        if (primaryFocus == null) {
            return prefix;
        }
        return switch (primaryFocus) {
            case COMBAT, HAZARD -> prefix + "卷进了一阵战斗，";
            case LABOR, BLOCK, TOOL, CREATION -> prefix + "手上一直没闲着，";
            case MOVEMENT, LANDSCAPE -> prefix + "还在外头奔走，";
            case SOCIAL, CREATURE -> prefix + "和周围的人事有些来往，";
            case STATUS, CONSUMABLE -> prefix + "先顾了一下自己，";
            default -> prefix;
        };
    }

    private String describeEntry(MovingActionSegmentEntry entry) {
        if (entry == null || entry.getType() == null) {
            return "";
        }

        String target = humanizeTarget(entry.getTarget());
        return switch (entry.getType()) {
            case ATTACK -> withCount("和" + fallbackTarget(target, "什么东西") + "交手", entry.getCount());
            case KILL -> withCount("解决了" + fallbackTarget(target, "一个敌人"), entry.getCount());
            case HURT_BY -> withCount("因" + fallbackTarget(target, "什么原因") + "被伤到", entry.getCount());
            case DEATH -> entry.getCount() > 1 ? "倒下了" + entry.getCount() + "次" : "倒下了一次";
            case MINE_BLOCK -> describeMineEntry(entry);
            case PLACE_BLOCK -> describePlaceEntry(entry);
            case USE_GATHER_TOOL -> describeGatherEntry(entry, target);
            case EAT -> withCount("吃了" + fallbackTarget(target, "些食物"), entry.getCount());
            case USE_ITEM -> withCount("用了" + fallbackTarget(target, "手上的东西"), entry.getCount());
            case INTERACT_FUNCTION_BLOCK -> "在" + fallbackTarget(target, "某个工作台") + "前忙活了一阵";
            case CRAFT_ITEM -> withCount("做出了" + fallbackTarget(target, "些东西"), entry.getCount());
            case TRAVEL -> entry.getCount() > 1 ? "连着赶了几段路" : "赶了一段路";
            case IDLE -> entry.getCount() > 1 ? "断断续续发了几次呆" : "停下来发了一会儿呆";
            case BIOME_CHANGE -> "走进了" + fallbackTarget(target, "一片新地方");
            case DIM_CHANGE -> "进入了" + fallbackTarget(target, "另一个维度");
            case TRADE -> "和" + fallbackTarget(target, "某个商贩") + "做了交易";
            case INTERACT_NPC -> "和" + fallbackTarget(target, "某个路人") + "打了交道";
            case ACQUIRE_ITEM -> "";
        };
    }

    private String describeMineEntry(MovingActionSegmentEntry entry) {
        if (entry.isFolded()) {
            String targets = formatTargetList(entry.getFoldedTargets());
            return targets.isBlank()
                    ? "挖开了不少方块"
                    : "挖开了不少方块，主要是" + targets;
        }
        return withCount("挖开了" + fallbackTarget(humanizeTarget(entry.getTarget()), "一些方块"), entry.getCount());
    }

    private String describePlaceEntry(MovingActionSegmentEntry entry) {
        if (entry.isFolded()) {
            String targets = formatTargetList(entry.getFoldedTargets());
            return targets.isBlank()
                    ? "铺下了不少方块"
                    : "铺下了不少方块，主要是" + targets;
        }
        return withCount("放下了" + fallbackTarget(humanizeTarget(entry.getTarget()), "一些方块"), entry.getCount());
    }

    private String describeGatherEntry(MovingActionSegmentEntry entry, String target) {
        if ("fishing".equals(entry.getTarget())) {
            return entry.getCount() > 1 ? "接连抛竿钓了几次东西" : "抛竿钓了些东西";
        }
        return withCount("用工具采集了" + fallbackTarget(target, "采集目标"), entry.getCount());
    }

    private String withCount(String base, int count) {
        return withCount(base, count, "次");
    }

    private String withCount(String base, int count, String unit) {
        if (count <= 1) {
            return base;
        }
        if (unit == null || unit.isBlank()) {
            return base + " ×" + count;
        }
        return base + count + unit;
    }

    private String formatTargetList(List<String> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) {
            return "";
        }
        List<String> targets = rawTargets.stream()
                .map(this::humanizeTarget)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(3)
                .toList();
        return String.join("、", targets);
    }

    private String fallbackTarget(String target, String fallback) {
        return target == null || target.isBlank() ? fallback : target;
    }

    private String humanizeTarget(String target) {
        if (target == null || target.isBlank() || "unknown".equals(target)) {
            return "";
        }
        if (MovingActionSegment.FOLDED_BLOCK_TARGET.equals(target)) {
            return "各种方块";
        }
        if (target.startsWith("(") && target.contains(")→(")) {
            return "一大段路";
        }

        String normalized = target;
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator + 1 < normalized.length()) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        normalized = normalized.replace('/', ' ');
        normalized = normalized.replace('_', ' ');
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized;
    }
}
