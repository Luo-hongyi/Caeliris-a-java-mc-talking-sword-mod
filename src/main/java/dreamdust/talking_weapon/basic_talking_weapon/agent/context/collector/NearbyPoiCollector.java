package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.ImportanceRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POI 探查采集器：扫描玩家附近已加载区域中的 POI 方块（床、钟、职业方块等），
 * 按距离排序后写入 extras["nearby_poi"]。
 * <p>
 * 优化：仅使用 {@link PoiManager} 进行探查（单机/集成服务端），性能极高。
 * 不再提供基于方块状态扫描的回退方案，以避免不可控的性能损耗。
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
        if (player == null || level == null)
            return;

        BlockPos playerPos = player.blockPosition();
        int radiusBlocks = scanRadiusChunks * 16;
        Map<String, Double> poiDistances = new LinkedHashMap<>();

        // 仅使用 PoiManager (在单机/集成服务端有效，性能极高)
        ServerLevel serverLevel = resolveIntegratedServerLevel(level);
        if (serverLevel == null) {
            // 在远程服务器上，如果不扫描方块则无法获取 POI，
            // 按照需求删除回退逻辑，此处直接跳过采集。
            return;
        }

        PoiManager poiManager = serverLevel.getPoiManager();
        poiManager.findAll(holder -> true, p -> true, playerPos, radiusBlocks, PoiManager.Occupancy.ANY)
                .forEach(pos -> {
                    // 即使在服务端，我们也从传入的 level 读状态以保证一致性
                    BlockState state = level.getBlockState(pos);
                    String name = resolvePoiName(level, state);
                    double dist = Math.sqrt(playerPos.distSqr(pos));
                    poiDistances.merge(name, dist, Math::min);
                });

        if (poiDistances.isEmpty()) {
            return;
        }

        // 结果汇总
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(poiDistances.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        long now = System.currentTimeMillis();
        int count = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (count >= maxResults)
                break;

            String poiName = entry.getKey();
            double distance = entry.getValue();
            FocusTag focus = determineFocusTag(poiName);

            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(focus)
                    .detail(DetailTag.customSpecific(prettifyName(poiName)))
                    .description(String.format("注意到%s的特殊位置: %s", dreamdust.talking_weapon.basic_talking_weapon.agent.context.DistanceFuzzer.fuzzify(distance), prettifyName(poiName)))
                    .importance(ImportanceRegistry.getInstance().get("poi:default", 0.6f))
                    .timestamp(now)
                    .build());

            count++;
        }
    }

    private FocusTag determineFocusTag(String poiName) {
        if (poiName.contains("bed")) return FocusTag.STATUS;
        if (poiName.contains("nether_portal")) return FocusTag.MOVEMENT;

        Set<String> workstations = Set.of(
            "barrel", "blast_furnace", "brewing_stand", "cartography_table",
            "cauldron", "composter", "fletching_table", "grindstone",
            "lectern", "loom", "smoker", "smithing_table", "stonecutter"
        );
        for (String ws : workstations) {
            if (poiName.contains(ws)) return FocusTag.CREATION;
        }

        return FocusTag.BLOCK;
    }

    private ServerLevel resolveIntegratedServerLevel(Level level) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server == null)
                return null;
            return server.getLevel(level.dimension());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolvePoiName(Level level, BlockState state) {
        try {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (blockId == null)
                return state.getBlock().toString();

            String path = blockId.getPath();
            if (path.endsWith("_bed"))
                return "bed";
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
