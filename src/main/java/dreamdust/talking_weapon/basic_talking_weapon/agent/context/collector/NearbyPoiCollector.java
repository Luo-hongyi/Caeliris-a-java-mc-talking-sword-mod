package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POI 探查采集器：扫描玩家附近已加载区域中的 POI 方块（床、钟、职业方块等），
 * 按距离排序后写入 extras["nearby_poi"]。
 */
public class NearbyPoiCollector implements IContextCollector {

    private static final int DEFAULT_SCAN_RADIUS = 5;
    private static final int DEFAULT_MAX_RESULTS = 8;

    private final int scanRadiusChunks;
    private final int maxResults;

    public NearbyPoiCollector() {
        this(DEFAULT_SCAN_RADIUS, DEFAULT_MAX_RESULTS);
    }

    public NearbyPoiCollector(int scanRadiusChunks, int maxResults) {
        this.scanRadiusChunks = Math.max(1, scanRadiusChunks);
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public String getName() {
        return "nearby_poi";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null) return;

        BlockPos playerPos = player.blockPosition();
        int radiusBlocks = scanRadiusChunks * 16;

        int minY = Math.max(level.getMinBuildHeight(), playerPos.getY() - 12);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, playerPos.getY() + 12);

        Map<String, Double> poiDistances = new LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = playerPos.getX() - radiusBlocks; x <= playerPos.getX() + radiusBlocks; x++) {
            int chunkX = x >> 4;
            for (int z = playerPos.getZ() - radiusBlocks; z <= playerPos.getZ() + radiusBlocks; z++) {
                int chunkZ = z >> 4;
                if (!level.hasChunk(chunkX, chunkZ)) continue;

                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (PoiTypes.forState(state).isEmpty()) continue;

                    String name = resolvePoiName(level, state);
                    double dist = Math.sqrt(playerPos.distSqr(cursor));
                    poiDistances.merge(name, dist, Math::min);
                }
            }
        }

        if (poiDistances.isEmpty()) {
            builder.extra("nearby_poi", "附近没有发现POI");
            return;
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(poiDistances.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (count >= maxResults) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(prettifyName(entry.getKey()))
              .append("(约").append((int) entry.getValue().doubleValue()).append("格)");
            count++;
        }

        builder.extra("nearby_poi", sb.toString());
    }

    private String resolvePoiName(Level level, BlockState state) {
        try {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (blockId == null) return state.getBlock().toString();

            String path = blockId.getPath();
            if (path.endsWith("_bed")) return "bed";
            return blockId.toString();
        } catch (Exception e) {
            return state.getBlock().toString();
        }
    }

    private String prettifyName(String fullName) {
        if (fullName.startsWith("minecraft:")) {
            return fullName.substring("minecraft:".length());
        }
        return fullName;
    }
}
