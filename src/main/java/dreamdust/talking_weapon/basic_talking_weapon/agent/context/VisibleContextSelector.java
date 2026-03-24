package dreamdust.talking_weapon.basic_talking_weapon.agent.context;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.config.TalkingWeaponConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 统一的 visible context 选择器。
 * <p>
 * 将 prompt 层的“潜意识直通 + Top-K + 去重合并”规则抽出来，
 * 供真实 LLM 交互与内部默认快照共用。
 */
public final class VisibleContextSelector {

    private VisibleContextSelector() {
    }

    public static Selection select(List<TaggedContextEntry> rawEntries, String source, float concentration) {
        if (rawEntries == null || rawEntries.isEmpty()) {
            return Selection.empty();
        }

        ArrayList<TaggedContextEntry> safeEntries = new ArrayList<>();
        for (TaggedContextEntry entry : rawEntries) {
            if (entry != null) {
                safeEntries.add(entry);
            }
        }
        if (safeEntries.isEmpty()) {
            return Selection.empty();
        }

        List<TaggedContextEntry> subconsciousPicks = SubconsciousBooster.pick(safeEntries, concentration);

        ArrayList<TaggedContextEntry> promptEligibleEntries = new ArrayList<>();
        for (TaggedContextEntry entry : safeEntries) {
            if (!entry.isAssociativeOnly()) {
                promptEligibleEntries.add(entry);
            }
        }

        List<TaggedContextEntry> filteredEntries = AttentionFilter.filterAndTopN(
                promptEligibleEntries,
                resolveTopK(source),
                TalkingWeaponConfig.NOISE_THRESHOLD.get().floatValue(),
                concentration);

        LinkedHashSet<TaggedContextEntry> visibleSet = new LinkedHashSet<>(filteredEntries);
        visibleSet.addAll(subconsciousPicks);
        return new Selection(promptEligibleEntries, filteredEntries, subconsciousPicks, List.copyOf(visibleSet));
    }

    private static int resolveTopK(String source) {
        if ("biome_change".equals(source)) {
            return TalkingWeaponConfig.TOP_K_BIOME_CHANGE.get();
        }
        if ("player_chat".equals(source)) {
            return TalkingWeaponConfig.TOP_K_CHAT.get();
        }
        return TalkingWeaponConfig.TOP_K_RIGHT_CLICK.get();
    }

    public record Selection(
            List<TaggedContextEntry> promptEligibleEntries,
            List<TaggedContextEntry> filteredEntries,
            List<TaggedContextEntry> subconsciousPicks,
            List<TaggedContextEntry> visibleContexts) {

        private static final Selection EMPTY = new Selection(List.of(), List.of(), List.of(), List.of());

        public Selection {
            promptEligibleEntries = immutableCopy(promptEligibleEntries);
            filteredEntries = immutableCopy(filteredEntries);
            subconsciousPicks = immutableCopy(subconsciousPicks);
            visibleContexts = immutableCopy(visibleContexts);
        }

        public static Selection empty() {
            return EMPTY;
        }

        private static List<TaggedContextEntry> immutableCopy(List<TaggedContextEntry> entries) {
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            return List.copyOf(entries);
        }
    }
}
