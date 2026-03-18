package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 周围方块/地形特征采集器：扫描玩家周围方块，进行高维度的环境推理。
 * <p>
 * 产生两类输出：
 * <ol>
 * <li><b>显式环境推理</b>：输出 {@code custom_specific:*}（如 `custom_specific:cave`,
 * `custom_specific:lava_nearby`），能在提示词中可见。</li>
 * <li><b>隐式环境锚点</b>：将周围所有方块按类型输出打上 {@code native_specific:*}，但标记为
 * {@code associativeOnly = true}，不渲染到提示词中，仅供联想系统检索。</li>
 * </ol>
 * <p>
 * 使用原版 API，无外部依赖。
 */
public class SurroundingBlocksCollector implements IContextCollector {

    /** 扫描半径（方块数），默认 5 */
    private final int scanRadius;

    public SurroundingBlocksCollector() {
        this.scanRadius = 5;
    }

    public SurroundingBlocksCollector(int scanRadius) {
        this.scanRadius = Math.max(1, Math.min(scanRadius, 12));
    }

    @Override
    public String getName() {
        return "surrounding_blocks";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null)
            return;

        BlockPos playerPos = player.blockPosition();

        // ═══════════════════════════════════════════════════
        // 1. 脚下与头顶方块推测
        // ═══════════════════════════════════════════════════
        BlockPos belowPos = playerPos.below();
        BlockPos aboveHeadPos = playerPos.above(2);
        String blockBelow = level.getBlockState(belowPos).getBlock().getName().getString();
        String blockAbove = level.getBlockState(aboveHeadPos).getBlock().getName().getString();

        // ═══════════════════════════════════════════════════
        // 2. 区域方块统计与隐式锚点生成
        // ═══════════════════════════════════════════════════
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        Map<String, String> blockLabels = new HashMap<>();
        int waterCount = 0;
        int lavaCount = 0;

        BlockPos scanCenter = playerPos.above(1);

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    BlockPos pos = scanCenter.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);

                    // 跳过空气
                    if (state.isAir())
                        continue;

                    // 流体统计
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (state.is(Blocks.WATER) || state.is(Blocks.WATER_CAULDRON)) {
                            waterCount++;
                        } else if (state.is(Blocks.LAVA) || state.is(Blocks.LAVA_CAULDRON)) {
                            lavaCount++;
                        }
                    }

                    String blockId = resolveBlockId(state);
                    blockCounts.merge(blockId, 1, Integer::sum);
                    blockLabels.putIfAbsent(blockId, state.getBlock().getName().getString());
                }
            }
        }

        // --- 生成方块统计概述 (extras) ---
        List<Map.Entry<String, Integer>> sortedBlocks = new ArrayList<>(blockCounts.entrySet());
        sortedBlocks.sort((a, b) -> {
            double scoreA = a.getValue() * getBlockWeight(a.getKey(), blockLabels.get(a.getKey()));
            double scoreB = b.getValue() * getBlockWeight(b.getKey(), blockLabels.get(b.getKey()));
            return Double.compare(scoreB, scoreA);
        });
        int topN = Math.min(5, sortedBlocks.size());
        if (topN > 0) {
            StringBuilder blockSummary = new StringBuilder();
            for (int i = 0; i < topN; i++) {
                Map.Entry<String, Integer> entry = sortedBlocks.get(i);
                if (i > 0) blockSummary.append(", ");
                String label = blockLabels.getOrDefault(entry.getKey(), entry.getKey());
                blockSummary.append(label).append("×").append(entry.getValue());
            }
            builder.extra("surrounding_blocks_summary", "周围主要方块: " + blockSummary);
        }

        // --- 生成隐式关联方块 (Associative Only) ---
        for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
            String label = blockLabels.getOrDefault(entry.getKey(), entry.getKey());
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.NATIVE_SPECIFIC, entry.getKey())
                    .description("周围环境方块: " + label + " 数量: " + entry.getValue())
                    .importance(ImportanceRegistry.getInstance().get("native_specific:default", 0.3f))
                    .associativeOnly(true) // 核心：平时隐形，仅供记忆联想查收！
                    .build());
        }

        // ═══════════════════════════════════════════════════
        // 3. 地面特征生成 (ENV Tags)
        // ═══════════════════════════════════════════════════
        List<String> features = new ArrayList<>();
        boolean canSeeSky = level.canSeeSky(playerPos.above());
        int blockLight = level.getBrightness(LightLayer.BLOCK, playerPos);
        int skyLight = level.getBrightness(LightLayer.SKY, playerPos);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, playerPos.getX(), playerPos.getZ());
        int depthBelowSurface = Math.max(0, surfaceY - playerPos.getY());
        int naturalNearbyBlocks = 0;
        int structuralNearbyBlocks = 0;

        for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
            String blockId = entry.getKey();
            int count = entry.getValue();
            if (isNaturalCaveBlock(blockId)) {
                naturalNearbyBlocks += count;
            } else if (isStructuralBlock(blockId)) {
                structuralNearbyBlocks += count;
            }
        }

        // 3a. 露天 / 室内 / 洞穴推断
        if (canSeeSky) {
            features.add("露天环境");
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "exposed")
                    .description("处于露天环境中，视野开阔。脚踩: " + blockBelow)
                    .importance(ImportanceRegistry.getInstance().get("env:exposed", 0.6f))
                    .build());
        } else {
            int coverBlocksAbove = 0;
            int naturalRoofBlocksAbove = 0;
            int structuralRoofBlocksAbove = 0;
            boolean hitSky = false;
            BlockPos scan = playerPos.above();

            for (int i = 0; i < 64; i++) {
                scan = scan.above();
                if (level.canSeeSky(scan)) {
                    hitSky = true;
                    break;
                }
                BlockState state = level.getBlockState(scan);
                String blockId = resolveBlockId(state);
                if (state.isAir() || isIgnoredOverheadBlock(state, blockId)) {
                    continue;
                }
                coverBlocksAbove++;
                if (isNaturalCaveBlock(blockId)) {
                    naturalRoofBlocksAbove++;
                } else if (isStructuralBlock(blockId)) {
                    structuralRoofBlocksAbove++;
                }
            }

            if (!hitSky) {
                features.add("深层遮挡或地底");
                builder.addTaggedEntry(TaggedContextEntry.builder()
                        .focus(FocusTag.LANDSCAPE)
                        .detail(DetailTag.CUSTOM_SPECIFIC, "deep_indoors")
                        .description("位于头顶极深处被遮挡的建筑内部或极深地城。头顶逾 64 格不见天日。")
                        .importance(ImportanceRegistry.getInstance().get("env:deep_indoors", 0.8f))
                        .build());
            } else if (coverBlocksAbove <= 4 && depthBelowSurface <= 4) {
                features.add("浅层遮挡(室内房屋或树冠下)");
                builder.addTaggedEntry(TaggedContextEntry.builder()
                        .focus(FocusTag.LANDSCAPE)
                        .detail(DetailTag.CUSTOM_SPECIFIC, "indoors")
                        .description("处于浅层遮挡环境中，更像屋檐、树冠下或普通建筑内部，而非地下洞穴。")
                        .importance(ImportanceRegistry.getInstance().get("env:indoors", 0.45f))
                        .build());
            } else {
                boolean likelyUnderground = depthBelowSurface >= 6;
                boolean naturalRoofDominant = naturalRoofBlocksAbove >= 5
                        && naturalRoofBlocksAbove >= structuralRoofBlocksAbove;
                boolean naturalSurroundings = naturalNearbyBlocks >= Math.max(16, structuralNearbyBlocks);
                boolean caveLikeDarkness = skyLight <= 2;

                if (likelyUnderground && naturalRoofDominant && naturalSurroundings && caveLikeDarkness) {
                    features.add("洞穴或地下");
                    builder.addTaggedEntry(TaggedContextEntry.builder()
                            .focus(FocusTag.LANDSCAPE)
                            .detail(DetailTag.CUSTOM_SPECIFIC, "cave")
                            .description(
                                    "位于地下天然洞穴或厚重岩层下。离地表约 " + depthBelowSurface + " 格，头顶有效遮挡 "
                                            + coverBlocksAbove + " 层，自然岩层占主导。")
                            .importance(ImportanceRegistry.getInstance().get("env:cave", 0.8f))
                            .build());
                } else {
                    features.add("大型建筑内部或深层室内");
                    builder.addTaggedEntry(TaggedContextEntry.builder()
                            .focus(FocusTag.LANDSCAPE)
                            .detail(DetailTag.CUSTOM_SPECIFIC, "indoors")
                            .description(
                                    "位于较深的室内/建筑遮挡环境中。虽然不见天日，但更像大型建筑、地堡或人造空间，而不是天然洞穴。")
                            .importance(ImportanceRegistry.getInstance().get("env:indoors", 0.55f))
                            .build());
                }
            }
        }

        // 3b. 高度信息推断
        int y = playerPos.getY();
        if (y >= 100) {
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "high_altitude")
                    .description("位于高海拔处 (Y=" + y + ")。")
                    .importance(ImportanceRegistry.getInstance().get("env:high_altitude", 0.6f))
                    .build());
        }

        // 3c. 水体/岩浆推断
        if (lavaCount > 0) {
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.HAZARD)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "lava_nearby")
                    .description("极度危险！周围发现大量岩浆池。")
                    .importance(ImportanceRegistry.getInstance().get("env:lava_nearby", 0.9f))
                    .build());
        } else if (waterCount > 10) {
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "water_nearby")
                    .description("附近有大量水体。")
                    .importance(ImportanceRegistry.getInstance().get("env:water_nearby", 0.5f))
                    .build());
        }

        // 3d. 光照环境推断
        if (blockLight <= 3 && skyLight <= 3) {
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.HAZARD) // 黑暗带来危险
                    .detail(DetailTag.CUSTOM_SPECIFIC, "pitch_dark")
                    .description("环境漆黑一片，极易生成怪物。")
                    .importance(ImportanceRegistry.getInstance().get("env:pitch_dark", 0.85f))
                    .build());
        }

        // 3e. 悬崖检测 (落差过滤)
        int maxDrop = 0;
        for (int[] dir : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            BlockPos edge = playerPos.offset(dir[0] * 2, 0, dir[1] * 2);
            int drop = 0;
            for (int i = 0; i < 30; i++) {
                if (!level.getBlockState(edge.below(i)).isAir())
                    break;
                drop++;
            }
            maxDrop = Math.max(maxDrop, drop);
        }
        if (maxDrop >= 8) {
            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.HAZARD)
                    .detail(DetailTag.CUSTOM_SPECIFIC, "cliff")
                    .description("处于高落差悬崖边缘 (落差 > " + maxDrop + " 格)，小心脚下！")
                    .importance(ImportanceRegistry.getInstance().get("env:cliff", 0.7f))
                    .build());
        }
    }

    private double getBlockWeight(String blockId, String blockLabel) {
        String lower = (blockLabel != null && !blockLabel.isBlank() ? blockLabel : blockId).toLowerCase();
        // 对常见的基础方块降低 0.5 的排序权重，避免它们总是占据 Top N
        if (lower.contains("dirt") || lower.contains("stone") || lower.contains("泥土") || lower.contains("石头") 
            || lower.contains("圆石") || lower.contains("深板岩") || lower.contains("沙砾") || lower.contains("凝灰岩")) {
            return 0.5;
        }
        return 1.0;
    }

    private boolean isIgnoredOverheadBlock(BlockState state, String blockId) {
        String lower = blockId.toLowerCase(Locale.ROOT);
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.VINE)
                || lower.contains("leaf")
                || lower.contains("leaves")
                || lower.contains("log")
                || lower.contains("wood")
                || lower.contains("vine")
                || lower.contains("moss_carpet")
                || lower.contains("grass")
                || lower.contains("fern")
                || lower.contains("bush")
                || lower.contains("petal")
                || lower.contains("crop")
                || lower.contains("sapling");
    }

    private boolean isNaturalCaveBlock(String blockId) {
        String lower = blockId.toLowerCase(Locale.ROOT);
        if (isStructuralBlock(lower)) {
            return false;
        }
        return lower.contains("stone")
                || lower.contains("deepslate")
                || lower.contains("andesite")
                || lower.contains("diorite")
                || lower.contains("granite")
                || lower.contains("tuff")
                || lower.contains("dripstone")
                || lower.contains("calcite")
                || lower.contains("basalt")
                || lower.contains("blackstone")
                || lower.contains("netherrack")
                || lower.contains("end_stone")
                || lower.contains("sandstone")
                || lower.contains("gravel")
                || lower.contains("dirt")
                || lower.contains("mud")
                || lower.contains("clay")
                || lower.contains("terracotta")
                || lower.contains("sand")
                || lower.contains("snow_block")
                || lower.contains("powder_snow")
                || lower.contains("ice")
                || lower.contains("magma")
                || lower.contains("soul_sand")
                || lower.contains("soul_soil")
                || lower.contains("ore");
    }

    private boolean isStructuralBlock(String blockId) {
        String lower = blockId.toLowerCase(Locale.ROOT);
        return lower.contains("planks")
                || lower.contains("brick")
                || lower.contains("bricks")
                || lower.contains("tiles")
                || lower.contains("glass")
                || lower.contains("pane")
                || lower.contains("concrete")
                || lower.contains("wool")
                || lower.contains("carpet")
                || lower.contains("door")
                || lower.contains("trapdoor")
                || lower.contains("fence")
                || lower.contains("gate")
                || lower.contains("wall")
                || lower.contains("stairs")
                || lower.contains("slab")
                || lower.contains("pillar")
                || lower.contains("lamp")
                || lower.contains("lantern")
                || lower.contains("torch")
                || lower.contains("chain")
                || lower.contains("scaffold")
                || lower.contains("cobblestone")
                || lower.contains("polished")
                || lower.contains("cut_")
                || lower.contains("chiseled")
                || lower.contains("smooth_")
                || lower.contains("stripped_")
                || lower.contains("trap")
                || lower.contains("bed")
                || lower.contains("bookshelf");
    }

    private String resolveBlockId(BlockState state) {
        var id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null ? id.toString() : state.getBlock().getName().getString();
    }
}
