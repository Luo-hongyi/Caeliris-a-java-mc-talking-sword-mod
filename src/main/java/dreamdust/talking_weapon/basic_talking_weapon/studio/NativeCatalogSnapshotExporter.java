package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.TagPolicy;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出当前实例已知的 native tag / registry 快照，供 Talking Weapon Studio 使用。
 * <p>
 * 该快照是只读视图：
 * <ul>
 * <li>native_specific 主要来自 Forge / Vanilla registries</li>
 * <li>native_generic 主要来自 registry / holder 上的 tags</li>
 * </ul>
 */
public final class NativeCatalogSnapshotExporter {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String CONFIG_SUBDIR = "talking_weapon";
    private static final String SNAPSHOT_FILENAME = "native_catalog.snapshot.json";
    private static final String ICON_SUBDIR = "native_catalog_icons";
    private static final int SCHEMA_VERSION = 1;

    private NativeCatalogSnapshotExporter() {
    }

    public static ExportResult exportSnapshot() {
        SnapshotRoot root = buildSnapshot();

        Path output = getSnapshotPath();
        try {
            Files.createDirectories(output.getParent());
            try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            return new ExportResult(output, root.entries.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to export native catalog snapshot to " + output, e);
        }
    }

    public static SnapshotRoot buildSnapshot() {
        SnapshotRoot root = new SnapshotRoot();
        root.schemaVersion = SCHEMA_VERSION;
        root.generatedAt = OffsetDateTime.now().toString();
        root.generator = buildGenerator();
        root.instance = buildInstance();
        root.entries = collectEntries();
        return root;
    }

    public static Path getSnapshotPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(SNAPSHOT_FILENAME);
    }

    public static Path getIconDirectory() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CONFIG_SUBDIR)
                .resolve(ICON_SUBDIR);
    }

    private static GeneratorInfo buildGenerator() {
        GeneratorInfo info = new GeneratorInfo();
        info.modVersion = ModList.get().getModContainerById("talking_weapon")
                .flatMap(container -> container.getModInfo() != null
                        ? java.util.Optional.of(container.getModInfo().getVersion().toString())
                        : java.util.Optional.empty())
                .orElse("unknown");
        info.minecraftVersion = "1.20.1";
        info.forgeVersion = "47.4.10";
        return info;
    }

    private static InstanceInfo buildInstance() {
        Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        InstanceInfo info = new InstanceInfo();
        info.instanceName = gameDir.getFileName() != null ? gameDir.getFileName().toString() : "minecraft";
        info.instanceId = Integer.toHexString(gameDir.toString().hashCode());
        info.packVersion = "";
        return info;
    }

    private static List<SnapshotEntry> collectEntries() {
        List<SnapshotEntry> entries = new ArrayList<>();

        collectForgeRegistry(entries, "item", ForgeRegistries.ITEMS,
                NativeCatalogSnapshotExporter::displayNameOfItem,
                NativeCatalogSnapshotExporter::metadataOfItem);
        collectForgeRegistry(entries, "block", ForgeRegistries.BLOCKS,
                NativeCatalogSnapshotExporter::displayNameOfBlock,
                NativeCatalogSnapshotExporter::metadataOfBlock);
        collectForgeRegistry(entries, "entity_type", ForgeRegistries.ENTITY_TYPES,
                NativeCatalogSnapshotExporter::displayNameOfEntity,
                NativeCatalogSnapshotExporter::metadataOfEntity);
        collectForgeRegistry(entries, "biome", ForgeRegistries.BIOMES,
                NativeCatalogSnapshotExporter::displayNameOfBiome,
                NativeCatalogSnapshotExporter::metadataOfBiome);
        collectBuiltinRegistry(entries, "poi", BuiltInRegistries.POINT_OF_INTEREST_TYPE,
                NativeCatalogSnapshotExporter::displayNameOfPoi,
                NativeCatalogSnapshotExporter::metadataOfPoi);
        collectBuiltinRegistry(entries, "enchantment", BuiltInRegistries.ENCHANTMENT,
                NativeCatalogSnapshotExporter::displayNameOfEnchantment,
                NativeCatalogSnapshotExporter::metadataOfEnchantment);
        collectBuiltinRegistry(entries, "effect", BuiltInRegistries.MOB_EFFECT,
                NativeCatalogSnapshotExporter::displayNameOfEffect,
                NativeCatalogSnapshotExporter::metadataOfEffect);
        collectBuiltinRegistry(entries, "fluid", BuiltInRegistries.FLUID,
                NativeCatalogSnapshotExporter::displayNameOfFluid,
                NativeCatalogSnapshotExporter::metadataOfFluid);
        collectStructureRegistry(entries);
        collectDimensions(entries);

        entries = deduplicateEntries(entries);

        entries.sort(Comparator
                .comparing((SnapshotEntry e) -> e.kind)
                .thenComparing(e -> e.registryType)
                .thenComparing(e -> e.tag));
        return entries;
    }

    private static List<SnapshotEntry> deduplicateEntries(List<SnapshotEntry> entries) {
        LinkedHashMap<String, SnapshotEntry> deduped = new LinkedHashMap<>();
        for (SnapshotEntry entry : entries) {
            deduped.putIfAbsent(dedupKey(entry), entry);
        }
        return new ArrayList<>(deduped.values());
    }

    private static String dedupKey(SnapshotEntry entry) {
        return entry.kind + "|" + entry.registryType + "|" + entry.tag;
    }

    private static <T> void collectForgeRegistry(List<SnapshotEntry> out, String registryType,
            IForgeRegistry<T> registry, DisplayNameResolver<T> displayNameResolver,
            MetadataResolver<T> metadataResolver) {
        for (T value : registry) {
            ResourceLocation key = registry.getKey(value);
            if (key == null) {
                continue;
            }

            out.add(entry(
                    DetailTag.nativeSpecific(key.toString()),
                    registryType,
                    key,
                    key.getNamespace(),
                    "registry",
                    true,
                    safeDisplayName(displayNameResolver, value, key),
                    safeMetadata(metadataResolver, value, key)));

            var reverseTagOption = registry.tags().getReverseTag(value);
            if (reverseTagOption.isPresent()) {
                for (TagKey<T> tagKey : reverseTagOption.get().getTagKeys().toList()) {
                    ResourceLocation tagId = tagKey.location();
                    out.add(entry(
                            DetailTag.nativeGeneric(tagId.toString()),
                            registryType,
                            tagId,
                            tagId.getNamespace(),
                            "datapack_tag",
                            false,
                            fallbackDisplayName(tagId),
                            null));
                }
            }
        }
    }

    private static <T> void collectBuiltinRegistry(List<SnapshotEntry> out, String registryType,
            Registry<T> registry, DisplayNameResolver<T> displayNameResolver,
            MetadataResolver<T> metadataResolver) {
        for (T value : registry) {
            ResourceLocation key = registry.getKey(value);
            if (key == null) {
                continue;
            }

            out.add(entry(
                    DetailTag.nativeSpecific(key.toString()),
                    registryType,
                    key,
                    key.getNamespace(),
                    "registry",
                    true,
                    safeDisplayName(displayNameResolver, value, key),
                    safeMetadata(metadataResolver, value, key)));

            registry.getResourceKey(value)
                    .flatMap(registry::getHolder)
                    .ifPresent(holder -> appendHolderTags(out, registryType, holder));
        }
    }

    private static <T> void appendHolderTags(List<SnapshotEntry> out, String registryType, Holder.Reference<T> holder) {
        for (TagKey<T> tagKey : holder.tags().toList()) {
            ResourceLocation tagId = tagKey.location();
            out.add(entry(
                    DetailTag.nativeGeneric(tagId.toString()),
                    registryType,
                    tagId,
                    tagId.getNamespace(),
                    "datapack_tag",
                    false,
                    fallbackDisplayName(tagId),
                    null));
        }
    }

    private static void collectStructureRegistry(List<SnapshotEntry> out) {
        Level level = currentLevel();
        if (level == null) {
            return;
        }

        level.registryAccess().registry(Registries.STRUCTURE).ifPresentOrElse(registry -> {
            collectBuiltinRegistry(out, "structure", registry,
                    NativeCatalogSnapshotExporter::displayNameOfStructure,
                    NativeCatalogSnapshotExporter::metadataOfStructure);
        }, () -> {
            System.err.println("[TalkingWeapon/Studio] Structure registry missing in current level registry access; skipping structure export.");
        });
    }

    private static void collectDimensions(List<SnapshotEntry> out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }

        for (ResourceKey<Level> levelKey : mc.getConnection().levels()) {
            ResourceLocation id = levelKey.location();
            out.add(entry(
                    DetailTag.nativeSpecific(id.toString()),
                    "dimension",
                    id,
                    id.getNamespace(),
                    "registry",
                    true,
                    displayNameOfDimension(null, id),
                    metadataOfDimension(null, id)));
        }
    }

    private static Level currentLevel() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null ? mc.level : null;
    }

    private static SnapshotEntry entry(DetailTag detailTag, String registryType, ResourceLocation id,
            String sourceMod, String discoveredFrom, boolean promptVisible, String displayName,
            Map<String, String> metadata) {
        SnapshotEntry entry = new SnapshotEntry();
        entry.tag = detailTag.toString();
        entry.kind = detailTag.getPrefix();
        entry.registryType = registryType;
        entry.namespace = id.getNamespace();
        entry.path = id.getPath();
        entry.sourceMod = sourceMod;
        entry.displayName = displayName;
        entry.discoveredFrom = discoveredFrom;
        entry.promptVisible = promptVisible && TagPolicy.isPromptVisible(detailTag);
        LinkedHashMap<String, String> mergedMetadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        if (detailTag.isSpecific()) {
            mergedMetadata.put("iconRelativePath", buildIconRelativePath(detailTag, registryType, id));
        }
        entry.metadata = !mergedMetadata.isEmpty() ? mergedMetadata : null;
        return entry;
    }

    public static String buildIconRelativePath(DetailTag detailTag, String registryType, ResourceLocation id) {
        return ICON_SUBDIR
                + "/"
                + sanitizePathSegment(detailTag.getPrefix())
                + "/"
                + sanitizePathSegment(registryType)
                + "/"
                + sanitizePathSegment(id.getNamespace())
                + "/"
                + sanitizeFileStem(id.getPath())
                + ".png";
    }

    private static String sanitizePathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sanitizeFileStem(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('/', '_').replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String displayNameOfItem(Item item, ResourceLocation id) {
        try {
            return item.getDefaultInstance().getHoverName().getString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayNameOfBlock(Block block, ResourceLocation id) {
        try {
            return net.minecraft.network.chat.Component.translatable(block.getDescriptionId()).getString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayNameOfEntity(EntityType<?> type, ResourceLocation id) {
        try {
            return type.getDescription().getString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayNameOfBiome(Biome biome, ResourceLocation id) {
        return translatedOrNull(Util.makeDescriptionId("biome", id));
    }

    private static String displayNameOfPoi(PoiType poiType, ResourceLocation id) {
        return translatedOrNull(Util.makeDescriptionId("poi", id));
    }

    private static String displayNameOfEnchantment(Enchantment enchantment, ResourceLocation id) {
        try {
            return net.minecraft.network.chat.Component.translatable(enchantment.getDescriptionId()).getString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayNameOfEffect(MobEffect effect, ResourceLocation id) {
        try {
            return net.minecraft.network.chat.Component.translatable(effect.getDescriptionId()).getString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayNameOfFluid(Fluid fluid, ResourceLocation id) {
        try {
            return fluid.getFluidType().getDescription().getString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String displayNameOfStructure(Structure structure, ResourceLocation id) {
        return translatedOrNull(Util.makeDescriptionId("structure", id));
    }

    private static String displayNameOfDimension(Level level, ResourceLocation id) {
        return translatedOrNull(Util.makeDescriptionId("dimension", id));
    }

    private static String fallbackDisplayName(ResourceLocation id) {
        return id != null ? humanizePath(id.getPath()) : null;
    }

    private static <T> String safeDisplayName(DisplayNameResolver<T> resolver, T value, ResourceLocation fallbackId) {
        String result = resolver != null ? resolver.resolve(value, fallbackId) : null;
        if (result != null && !result.isBlank()) {
            return result;
        }
        return fallbackDisplayName(fallbackId);
    }

    private static <T> Map<String, String> safeMetadata(MetadataResolver<T> resolver, T value, ResourceLocation id) {
        if (resolver == null) {
            return null;
        }
        Map<String, String> metadata = resolver.resolve(value, id);
        return metadata == null || metadata.isEmpty() ? null : metadata;
    }

    private static Map<String, String> metadataOfItem(Item item, ResourceLocation id) {
        LinkedHashMap<String, String> metadata = baseTranslationMetadata(item.getDescriptionId());
        metadata.put("iconKind", "item");
        metadata.put("iconId", id.toString());
        return metadata;
    }

    private static Map<String, String> metadataOfBlock(Block block, ResourceLocation id) {
        LinkedHashMap<String, String> metadata = baseTranslationMetadata(block.getDescriptionId());
        Item blockItem = block.asItem();
        if (blockItem != Items.AIR) {
            ResourceLocation blockItemId = ForgeRegistries.ITEMS.getKey(blockItem);
            if (blockItemId != null) {
                metadata.put("iconKind", "item");
                metadata.put("iconId", blockItemId.toString());
            }
        } else {
            metadata.put("iconKind", "block");
            metadata.put("iconId", id.toString());
        }
        return metadata;
    }

    private static Map<String, String> metadataOfEntity(EntityType<?> type, ResourceLocation id) {
        LinkedHashMap<String, String> metadata = baseTranslationMetadata(type.getDescriptionId());
        SpawnEggItem spawnEggItem = SpawnEggItem.byId(type);
        if (spawnEggItem != null) {
            ResourceLocation eggId = ForgeRegistries.ITEMS.getKey(spawnEggItem);
            if (eggId != null) {
                metadata.put("iconKind", "item");
                metadata.put("iconId", eggId.toString());
            }
        }
        return metadata;
    }

    private static Map<String, String> metadataOfBiome(Biome biome, ResourceLocation id) {
        return baseTranslationMetadata(Util.makeDescriptionId("biome", id));
    }

    private static Map<String, String> metadataOfPoi(PoiType poiType, ResourceLocation id) {
        return baseTranslationMetadata(Util.makeDescriptionId("poi", id));
    }

    private static Map<String, String> metadataOfEnchantment(Enchantment enchantment, ResourceLocation id) {
        return baseTranslationMetadata(enchantment.getDescriptionId());
    }

    private static Map<String, String> metadataOfEffect(MobEffect effect, ResourceLocation id) {
        return baseTranslationMetadata(effect.getDescriptionId());
    }

    private static Map<String, String> metadataOfFluid(Fluid fluid, ResourceLocation id) {
        LinkedHashMap<String, String> metadata = baseTranslationMetadata(safeFluidTranslationKey(fluid));
        Item bucket = fluid.getBucket();
        if (bucket != Items.AIR) {
            ResourceLocation bucketId = ForgeRegistries.ITEMS.getKey(bucket);
            if (bucketId != null) {
                metadata.put("iconKind", "item");
                metadata.put("iconId", bucketId.toString());
            }
        }
        return metadata;
    }

    private static Map<String, String> metadataOfStructure(Structure structure, ResourceLocation id) {
        return baseTranslationMetadata(Util.makeDescriptionId("structure", id));
    }

    private static Map<String, String> metadataOfDimension(Level level, ResourceLocation id) {
        return baseTranslationMetadata(Util.makeDescriptionId("dimension", id));
    }

    private static LinkedHashMap<String, String> baseTranslationMetadata(String translationKey) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        if (translationKey != null && !translationKey.isBlank()) {
            metadata.put("translationKey", translationKey);
        }
        return metadata;
    }

    private static String safeFluidTranslationKey(Fluid fluid) {
        try {
            return fluid.getFluidType().getDescriptionId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String translatedOrNull(String translationKey) {
        if (translationKey == null || translationKey.isBlank()) {
            return null;
        }
        try {
            String resolved = net.minecraft.network.chat.Component.translatable(translationKey).getString();
            if (resolved != null && !resolved.isBlank() && !resolved.equals(translationKey)) {
                return resolved;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String humanizePath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        return path.replace('_', ' ');
    }

    @FunctionalInterface
    private interface DisplayNameResolver<T> {
        String resolve(T value, ResourceLocation id);
    }

    @FunctionalInterface
    private interface MetadataResolver<T> {
        Map<String, String> resolve(T value, ResourceLocation id);
    }

    public static final class ExportResult {
        public final Path outputPath;
        public final int entryCount;

        public ExportResult(Path outputPath, int entryCount) {
            this.outputPath = outputPath;
            this.entryCount = entryCount;
        }
    }

    public static final class SnapshotRoot {
        public int schemaVersion;
        public String generatedAt;
        public GeneratorInfo generator;
        public InstanceInfo instance;
        public List<SnapshotEntry> entries;
    }

    public static final class GeneratorInfo {
        public String modVersion;
        public String minecraftVersion;
        public String forgeVersion;
    }

    public static final class InstanceInfo {
        public String instanceId;
        public String instanceName;
        public String packVersion;
    }

    public static final class SnapshotEntry {
        public String tag;
        public String kind;
        public String registryType;
        public String namespace;
        public String path;
        public String sourceMod;
        public String displayName;
        public String discoveredFrom;
        @SerializedName("promptVisible")
        public boolean promptVisible;
        public Map<String, String> metadata;
    }
}
