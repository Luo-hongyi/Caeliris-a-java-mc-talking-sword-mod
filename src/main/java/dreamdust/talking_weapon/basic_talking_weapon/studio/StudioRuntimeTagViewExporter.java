package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDefinition;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagDescriptionDictionary;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagRelationDictionary;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导出 Studio 使用的运行时 tag 合并视图，并附带基础校验结果。
 * <p>
 * 合并来源：
 * <ul>
 * <li>当前实例运行时 native catalog</li>
 * <li>tag_descriptions.json</li>
 * <li>tag_relations.json</li>
 * </ul>
 */
public final class StudioRuntimeTagViewExporter {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String OUTPUT_FILENAME = "studio_runtime_tag_view.json";
    private static final int SCHEMA_VERSION = 1;

    private StudioRuntimeTagViewExporter() {
    }

    public static ExportResult exportMergedView() {
        ViewRoot root = buildMergedView();
        Path output = getOutputPath();
        try {
            Files.createDirectories(output.getParent());
            try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            return new ExportResult(output, root.summary.totalTags, root.validation.issueCount);
        } catch (IOException e) {
            throw new RuntimeException("Failed to export studio runtime tag view to " + output, e);
        }
    }

    public static Path getOutputPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(OUTPUT_FILENAME);
    }

    public static ViewRoot buildMergedView() {
        NativeCatalogSnapshotExporter.SnapshotRoot snapshot = NativeCatalogSnapshotExporter.buildSnapshot();
        Map<String, TagDefinition> descriptions = TagDescriptionDictionary.getInstance().getAllEntries();
        Map<String, List<String>> relations = TagRelationDictionary.getInstance().getAllEntries();
        TagRelationDictionary relationDict = TagRelationDictionary.getInstance();

        Map<String, TagViewEntry> tags = new LinkedHashMap<>();

        for (NativeCatalogSnapshotExporter.SnapshotEntry snapshotEntry : snapshot.entries) {
            TagViewEntry entry = ensureTag(tags, snapshotEntry.tag);
            addUnique(entry.registryTypes, snapshotEntry.registryType);
            addUnique(entry.sourceMods, snapshotEntry.sourceMod);
            addUnique(entry.discoveredIn, "native_catalog");
            entry.promptVisible = snapshotEntry.promptVisible;
        }

        for (Map.Entry<String, TagDefinition> descriptionEntry : descriptions.entrySet()) {
            TagViewEntry entry = ensureTag(tags, descriptionEntry.getKey());
            entry.description = descriptionEntry.getValue() != null ? descriptionEntry.getValue().getDescription() : "";
            addUnique(entry.discoveredIn, "description");
        }

        for (Map.Entry<String, List<String>> relationEntry : relations.entrySet()) {
            TagViewEntry source = ensureTag(tags, relationEntry.getKey());
            addUnique(source.discoveredIn, "relation_source");
            for (String targetTag : relationEntry.getValue()) {
                addUnique(source.directRelationTargets, targetTag);
                TagViewEntry target = ensureTag(tags, targetTag);
                addUnique(target.discoveredIn, "relation_target");
            }
        }

        List<ValidationIssue> issues = new ArrayList<>();
        int nativeCount = 0;
        int customCount = 0;
        int promptVisibleCount = 0;
        int describedCount = 0;
        int relationEdgeCount = 0;

        for (TagViewEntry entry : tags.values()) {
            if (entry.tag.startsWith("native_")) {
                nativeCount++;
            } else {
                customCount++;
            }
            if (entry.promptVisible) {
                promptVisibleCount++;
            }
            if (entry.description != null && !entry.description.isBlank()) {
                describedCount++;
            }
            relationEdgeCount += entry.directRelationTargets.size();

            Set<String> expanded = relationDict.expand(entry.tag);
            expanded.remove(entry.tag);
            entry.transitiveRelationTargets.addAll(expanded);

            validateEntry(entry, issues);
        }

        List<TagViewEntry> sortedTags = new ArrayList<>(tags.values());
        sortedTags.sort(Comparator.comparing(tag -> tag.tag));

        List<ValidationIssue> sortedIssues = new ArrayList<>(issues);
        sortedIssues.sort(Comparator
                .comparing((ValidationIssue issue) -> issue.severity)
                .thenComparing(issue -> issue.tag == null ? "" : issue.tag)
                .thenComparing(issue -> issue.code));

        ViewRoot root = new ViewRoot();
        root.schemaVersion = SCHEMA_VERSION;
        root.generatedAt = OffsetDateTime.now().toString();
        root.nativeCatalogGeneratedAt = snapshot.generatedAt;
        root.tags = sortedTags;
        root.summary = new Summary();
        root.summary.totalTags = sortedTags.size();
        root.summary.nativeTags = nativeCount;
        root.summary.customTags = customCount;
        root.summary.promptVisibleTags = promptVisibleCount;
        root.summary.describedTags = describedCount;
        root.summary.relationSourceTags = relations.size();
        root.summary.relationEdges = relationEdgeCount;
        root.validation = new Validation();
        root.validation.issueCount = sortedIssues.size();
        root.validation.issues = sortedIssues;
        return root;
    }

    private static TagViewEntry ensureTag(Map<String, TagViewEntry> tags, String rawTag) {
        return tags.computeIfAbsent(rawTag, StudioRuntimeTagViewExporter::newEntry);
    }

    private static TagViewEntry newEntry(String rawTag) {
        DetailTag tag = DetailTag.parse(rawTag);
        TagViewEntry entry = new TagViewEntry();
        entry.tag = rawTag;
        entry.kind = tag.getPrefix();
        entry.promptVisible = TagPolicy.isPromptVisible(tag);
        return entry;
    }

    private static void validateEntry(TagViewEntry entry, List<ValidationIssue> issues) {
        boolean hasDescription = entry.description != null && !entry.description.isBlank();
        boolean anchored = !entry.registryTypes.isEmpty() || hasDescription;

        if (entry.tag.startsWith("custom_") && !hasDescription) {
            issues.add(issue("error", "custom_tag_missing_description", entry.tag,
                    "Custom tag must be registered in tag_descriptions.json."));
        }

        if (entry.promptVisible && !hasDescription) {
            issues.add(issue("warning", "prompt_visible_tag_missing_description", entry.tag,
                    "Prompt-visible tag currently has no human-readable description."));
        }

        if (!anchored && (!entry.directRelationTargets.isEmpty() || entry.discoveredIn.contains("relation_target"))) {
            issues.add(issue("warning", "relation_tag_without_anchor", entry.tag,
                    "Tag only appears in relation graph and has neither native catalog anchor nor description."));
        }
    }

    private static ValidationIssue issue(String severity, String code, String tag, String message) {
        ValidationIssue issue = new ValidationIssue();
        issue.severity = severity;
        issue.code = code;
        issue.tag = tag;
        issue.message = message;
        return issue;
    }

    private static void addUnique(Collection<String> out, String value) {
        if (value != null && !value.isBlank()) {
            out.add(value);
        }
    }

    public static final class ExportResult {
        public final Path outputPath;
        public final int tagCount;
        public final int issueCount;

        public ExportResult(Path outputPath, int tagCount, int issueCount) {
            this.outputPath = outputPath;
            this.tagCount = tagCount;
            this.issueCount = issueCount;
        }
    }

    public static final class ViewRoot {
        public int schemaVersion;
        public String generatedAt;
        public String nativeCatalogGeneratedAt;
        public Summary summary;
        public Validation validation;
        public List<TagViewEntry> tags;
    }

    public static final class Summary {
        public int totalTags;
        public int nativeTags;
        public int customTags;
        public int promptVisibleTags;
        public int describedTags;
        public int relationSourceTags;
        public int relationEdges;
    }

    public static final class Validation {
        public int issueCount;
        public List<ValidationIssue> issues;
    }

    public static final class ValidationIssue {
        public String severity;
        public String code;
        public String tag;
        public String message;
    }

    public static final class TagViewEntry {
        public String tag;
        public String kind;
        @SerializedName("promptVisible")
        public boolean promptVisible;
        public String description;
        public final Set<String> registryTypes = new LinkedHashSet<>();
        public final Set<String> sourceMods = new LinkedHashSet<>();
        public final Set<String> discoveredIn = new LinkedHashSet<>();
        public final Set<String> directRelationTargets = new LinkedHashSet<>();
        public final Set<String> transitiveRelationTargets = new LinkedHashSet<>();
    }
}
