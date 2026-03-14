package dreamdust.talking_weapon.basic_talking_weapon.agent.context.collector;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.AgentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.FocusTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.*;

/**
 * 结构探查采集器：扫描玩家周围一定区块半径内的结构（如村庄、要塞、神殿等），
 * 按距离排序后写入 extras["nearby_structures"]。
 * <p>
 * 使用原版 {@link ChunkAccess#getAllStarts()} API 读取已加载区块中的结构信息，
 * 无需任何外部依赖。仅扫描已加载的区块，不会触发新区块的加载。
 *
 * <h3>配置</h3>
 * <ul>
 * <li>{@code scanRadiusChunks} — 扫描半径（区块），默认 5（约 80 格）</li>
 * <li>{@code maxResults} — 最多报告的结构数量，默认 8</li>
 * </ul>
 */
public class NearbyStructureCollector implements IContextCollector {

    /** 默认扫描半径（区块数） */
    private static final int DEFAULT_SCAN_RADIUS = 5;

    /** 默认最大报告数量 */
    private static final int DEFAULT_MAX_RESULTS = 8;

    /** 扫描半径（区块数），1 区块 = 16 格 */
    private final int scanRadiusChunks;

    /** 最多报告多少个结构 */
    private final int maxResults;

    public NearbyStructureCollector() {
        this(DEFAULT_SCAN_RADIUS, DEFAULT_MAX_RESULTS);
    }

    public NearbyStructureCollector(int scanRadiusChunks) {
        this(scanRadiusChunks, DEFAULT_MAX_RESULTS);
    }

    public NearbyStructureCollector(int scanRadiusChunks, int maxResults) {
        this.scanRadiusChunks = Math.max(1, scanRadiusChunks);
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public String getName() {
        return "nearby_structures";
    }

    @Override
    public void collect(AgentContext.Builder builder, Player player, Level level) {
        if (player == null || level == null)
            return;

        BlockPos playerPos = player.blockPosition();
        Map<String, Double> structureDistances = collectStructureDistances(level, playerPos);

        // 客户端世界下可能拿不到完整结构起点，单机时回退到集成服务端再扫一次
        if (structureDistances.isEmpty() && level.isClientSide) {
            ServerLevel serverLevel = resolveIntegratedServerLevel(level);
            if (serverLevel != null) {
                structureDistances = collectStructureDistances(serverLevel, playerPos);
            }
        }

        if (structureDistances.isEmpty()) {
            return;
        }

        // 按距离升序排列，取前 maxResults 个
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(structureDistances.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        long now = System.currentTimeMillis();
        int count = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (count >= maxResults)
                break;

            String structureId = entry.getKey();
            double distance = entry.getValue();

            builder.addTaggedEntry(TaggedContextEntry.builder()
                    .focus(FocusTag.LANDSCAPE)
                    .detail(DetailTag.of(DetailTag.STRUC, structureId))
                    .description(String.format("注意到附近的结构: %s (约 %d 格)", prettifyName(structureId), (int) distance))
                    .importance(distance < 32 ? 0.8f : 0.5f) // 距离越近权重越高
                    .timestamp(now)
                    .build());

            count++;
        }
    }

    private Map<String, Double> collectStructureDistances(Level level, BlockPos playerPos) {
        int centerCX = SectionPos.blockToSectionCoord(playerPos.getX());
        int centerCZ = SectionPos.blockToSectionCoord(playerPos.getZ());

        Map<String, Double> structureDistances = new LinkedHashMap<>();

        for (int dx = -scanRadiusChunks; dx <= scanRadiusChunks; dx++) {
            for (int dz = -scanRadiusChunks; dz <= scanRadiusChunks; dz++) {
                int cx = centerCX + dx;
                int cz = centerCZ + dz;

                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, false);
                if (chunk == null)
                    continue;

                Map<Structure, StructureStart> starts = chunk.getAllStarts();
                if (starts == null || starts.isEmpty())
                    continue;

                for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                    StructureStart start = entry.getValue();
                    if (start == null || !start.isValid())
                        continue;

                    String name = resolveStructureName(level, entry.getKey());
                    BoundingBox bb = start.getBoundingBox();
                    BlockPos center = bb.getCenter();
                    double dist = Math.sqrt(playerPos.distSqr(center));

                    structureDistances.merge(name, dist, Math::min);
                }
            }
        }

        return structureDistances;
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

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 通过注册表获取结构的 ResourceLocation 名称。
     * 若注册表中找不到则返回对象 toString。
     */
    private String resolveStructureName(Level level, Structure structure) {
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            ResourceLocation id = registry.getKey(structure);
            return id != null ? id.toString() : structure.toString();
        } catch (Exception e) {
            return structure.toString();
        }
    }

    /**
     * 美化结构名称：将 "minecraft:village_plains" 之类的名称
     * 转为更可读的形式 "village_plains"（去掉 minecraft: 前缀）。
     */
    private String prettifyName(String fullName) {
        if (fullName.startsWith("minecraft:")) {
            return fullName.substring("minecraft:".length());
        }
        return fullName;
    }
}
