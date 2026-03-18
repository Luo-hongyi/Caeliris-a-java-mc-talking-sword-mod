package dreamdust.talking_weapon.basic_talking_weapon.studio;

import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDefinition;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用当前实例导出的 native catalog 显示名，补全缺失的 native tag description。
 * <p>
 * 默认只补全缺失项，不覆盖用户已手写的描述。
 */
public final class NativeTagDescriptionSeeder {

    private NativeTagDescriptionSeeder() {
    }

    public static SeedResult seedMissingNativeDescriptions() {
        return seedNativeDescriptions(false);
    }

    public static SeedResult seedNativeDescriptions(boolean overwriteExisting) {
        TagDescriptionDictionary dictionary = TagDescriptionDictionary.getInstance();
        Map<String, TagDefinition> merged = new LinkedHashMap<>(dictionary.getAllEntries());
        Map<String, String> nativeDescriptions = collectNativeDescriptions();

        int added = 0;
        int updated = 0;
        int skipped = 0;

        for (Map.Entry<String, String> entry : nativeDescriptions.entrySet()) {
            String tag = entry.getKey();
            String description = entry.getValue();
            if (description == null || description.isBlank()) {
                skipped++;
                continue;
            }

            TagDefinition existing = merged.get(tag);
            if (existing == null || existing.getDescription().isBlank()) {
                merged.put(tag, new TagDefinition(description));
                added++;
                continue;
            }

            if (overwriteExisting && !existing.getDescription().equals(description)) {
                merged.put(tag, new TagDefinition(description));
                updated++;
            } else {
                skipped++;
            }
        }

        if (added > 0 || updated > 0) {
            dictionary.updateAll(merged);
        }

        return new SeedResult(TagDescriptionDictionary.getConfigPath(), nativeDescriptions.size(), added, updated, skipped);
    }

    private static Map<String, String> collectNativeDescriptions() {
        NativeCatalogSnapshotExporter.SnapshotRoot snapshot = NativeCatalogSnapshotExporter.buildSnapshot();
        Map<String, String> descriptions = new LinkedHashMap<>();

        for (NativeCatalogSnapshotExporter.SnapshotEntry entry : snapshot.entries) {
            if (!entry.tag.startsWith("native_")) {
                continue;
            }
            if (entry.displayName == null || entry.displayName.isBlank()) {
                continue;
            }
            descriptions.putIfAbsent(entry.tag, entry.displayName);
        }
        return descriptions;
    }

    public static final class SeedResult {
        public final Path outputPath;
        public final int candidateCount;
        public final int addedCount;
        public final int updatedCount;
        public final int skippedCount;

        public SeedResult(Path outputPath, int candidateCount, int addedCount, int updatedCount, int skippedCount) {
            this.outputPath = outputPath;
            this.candidateCount = candidateCount;
            this.addedCount = addedCount;
            this.updatedCount = updatedCount;
            this.skippedCount = skippedCount;
        }
    }
}
