package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

/**
 * 周围方块/地形特征采集器：扫描玩家周围方块，生成可读的环境描述，
 * 写入 {@code extras["surrounding_blocks"]} 和 {@code extras["terrain_features"]}。
 * <p>
 * 采集内容：
 * <ul>
 *   <li><b>脚下/头顶方块</b> — 玩家脚下和头部上方各 1 格</li>
 *   <li><b>方块组成统计</b> — 玩家周围区域内最多出现的非空气方块 Top-N</li>
 *   <li><b>地形特征</b> — 室内/露天、洞穴、附近水体/岩浆、高度信息</li>
 * </ul>
 * <p>
 * 使用原版 API，无外部依赖。
 */
public class SurroundingBlocksCollector implements IContextCollector {

    /** 扫描半径（方块数），默认 5 */
    private final int scanRadius;

    /** 方块统计 Top-N */
    private final int topN;

    public SurroundingBlocksCollector() {
        this(5, 8);
    }

    public SurroundingBlocksCollector(int scanRadius, int topN) {
        this.scanRadius = Math.max(1, Math.min(scanRadius, 12));
        this.topN = Math.max(3, Math.min(topN, 20));
    }

    @Override
    public String getName() {
        return "surrounding_blocks";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null) return;

        BlockPos playerPos = player.blockPosition();

        // ═══════════════════════════════════════════════════
        //  1. 脚下与头顶方块
        // ═══════════════════════════════════════════════════
        BlockPos belowPos = playerPos.below();
        BlockPos aboveHeadPos = playerPos.above(2); // 玩家身高约 1.8 格，above(2) 是头顶上方
        String blockBelow = level.getBlockState(belowPos).getBlock().getName().getString();
        String blockAbove = level.getBlockState(aboveHeadPos).getBlock().getName().getString();

        // ═══════════════════════════════════════════════════
        //  2. 区域方块统计
        // ═══════════════════════════════════════════════════
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        int waterCount = 0;
        int lavaCount = 0;

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);

                    // 跳过空气
                    if (state.isAir()) continue;

                    // 流体统计
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (state.is(Blocks.WATER) || state.is(Blocks.WATER_CAULDRON)) {
                            waterCount++;
                        } else if (state.is(Blocks.LAVA) || state.is(Blocks.LAVA_CAULDRON)) {
                            lavaCount++;
                        }
                    }

                    String name = state.getBlock().getName().getString();
                    blockCounts.merge(name, 1, Integer::sum);
                }
            }
        }

        // 按数量降序排序，取 Top-N
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(blockCounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int limit = Math.min(sorted.size(), topN);

        StringBuilder blocksSb = new StringBuilder();
        blocksSb.append("脚下: ").append(blockBelow).append(", 头顶: ").append(blockAbove);
        if (limit > 0) {
            blocksSb.append("; 周围主要方块: ");
            for (int i = 0; i < limit; i++) {
                if (i > 0) blocksSb.append(", ");
                Map.Entry<String, Integer> entry = sorted.get(i);
                blocksSb.append(entry.getKey()).append("×").append(entry.getValue());
            }
        }

        builder.extra("surrounding_blocks", blocksSb.toString());

        // ═══════════════════════════════════════════════════
        //  3. 地形特征
        // ═══════════════════════════════════════════════════
        List<String> features = new ArrayList<>();

        // 3a. 露天 / 室内 / 洞穴
        boolean canSeeSky = level.canSeeSky(playerPos.above());
        if (canSeeSky) {
            features.add("露天环境");
        } else {
            // 检测距地表距离（向上扫描找到天空的距离）
            int depthToSky = 0;
            BlockPos scan = playerPos.above();
            for (int i = 0; i < 64; i++) {
                scan = scan.above();
                if (level.canSeeSky(scan)) {
                    depthToSky = i + 1;
                    break;
                }
            }
            if (depthToSky == 0) {
                features.add("深层洞穴或建筑内部(头顶64格内无天空)");
            } else if (depthToSky <= 5) {
                features.add("浅层遮挡(距天空约" + depthToSky + "格，可能是树下或屋檐)");
            } else {
                features.add("洞穴或地下(距地表约" + depthToSky + "格)");
            }
        }

        // 3b. 高度信息
        int y = playerPos.getY();
        if (y >= 200) {
            features.add("极高处(Y=" + y + ")");
        } else if (y >= 100) {
            features.add("山地/高处(Y=" + y + ")");
        } else if (y <= 0) {
            features.add("深暗之域深度(Y=" + y + ")");
        } else if (y <= 30) {
            features.add("地下(Y=" + y + ")");
        }

        // 3c. 附近水体/岩浆
        if (waterCount > 10) {
            features.add("附近有大量水体");
        } else if (waterCount > 0) {
            features.add("附近有少量水");
        }
        if (lavaCount > 10) {
            features.add("附近有大量岩浆(危险!)");
        } else if (lavaCount > 0) {
            features.add("附近有岩浆");
        }

        // 3d. 光照环境
        int blockLight = level.getBrightness(LightLayer.BLOCK, playerPos);
        int skyLight = level.getBrightness(LightLayer.SKY, playerPos);
        if (blockLight <= 3 && skyLight <= 3) {
            features.add("极暗环境(怪物可能生成)");
        } else if (blockLight <= 7 && skyLight <= 7) {
            features.add("昏暗环境");
        }

        // 3e. 是否在水中
        if (player.isInWater()) {
            features.add("玩家在水中");
        }
        if (player.isInLava()) {
            features.add("玩家在岩浆中");
        }

        // 3f. 玩家四周悬崖检测（四个正方向向下扫描）
        int maxDrop = 0;
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos edge = playerPos.offset(dir[0] * 2, 0, dir[1] * 2);
            int drop = 0;
            for (int i = 0; i < 30; i++) {
                if (!level.getBlockState(edge.below(i)).isAir()) break;
                drop++;
            }
            maxDrop = Math.max(maxDrop, drop);
        }
        if (maxDrop >= 20) {
            features.add("附近有悬崖(落差>" + maxDrop + "格)");
        } else if (maxDrop >= 8) {
            features.add("附近有较高落差(" + maxDrop + "格)");
        }

        if (!features.isEmpty()) {
            builder.extra("terrain_features", String.join("; ", features));
        }
    }
}
