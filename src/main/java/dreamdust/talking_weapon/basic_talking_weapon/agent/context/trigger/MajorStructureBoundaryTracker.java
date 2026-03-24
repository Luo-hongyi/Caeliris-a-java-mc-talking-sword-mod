package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionTimelineRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * major structure enter / exit 边界 tracker。
 * <p>
 * 白名单直接消费现有 {@code DetailTag} 字符串，
 * 当前默认要求结构 tag 命中 runtime 白名单后才会参与边界动作产出。
 */
public final class MajorStructureBoundaryTracker {

    private static final int MIN_SCAN_RADIUS_CHUNKS = 5;
    private static final int STRUCTURE_SCAN_MARGIN_BLOCKS = 96;

    private boolean initialized;
    private TrackedStructure currentStructure;

    public List<ActionRecord> update(Player player, Level level) {
        if (player == null || level == null) {
            return List.of();
        }

        if (ActionTimelineRuntimeSettings.getMajorStructureWhitelist().isEmpty()) {
            initialized = true;
            currentStructure = null;
            return List.of();
        }

        String dimensionId = level.dimension().location().toString();
        BlockPos playerPos = player.blockPosition();

        if (!initialized) {
            initialized = true;
            currentStructure = detectNearestMajorStructure(level, playerPos, dimensionId,
                    ActionTimelineRuntimeSettings.getMajorStructureEnterRadiusBlocks());
            return List.of();
        }

        ArrayList<ActionRecord> records = new ArrayList<>(2);
        if (currentStructure != null && !isWithinExitRange(currentStructure, playerPos, dimensionId)) {
            records.add(buildBoundaryRecord(ActionType.EXIT_MAJOR_STRUCTURE, currentStructure, false));
            currentStructure = null;
        }

        if (currentStructure == null) {
            TrackedStructure enterCandidate = detectNearestMajorStructure(
                    level,
                    playerPos,
                    dimensionId,
                    ActionTimelineRuntimeSettings.getMajorStructureEnterRadiusBlocks());
            if (enterCandidate != null) {
                currentStructure = enterCandidate;
                records.add(buildBoundaryRecord(ActionType.ENTER_MAJOR_STRUCTURE, currentStructure, true));
            }
        }

        return records.isEmpty() ? List.of() : List.copyOf(records);
    }

    private boolean isWithinExitRange(TrackedStructure structure, BlockPos playerPos, String dimensionId) {
        if (structure == null || playerPos == null || dimensionId == null || !dimensionId.equals(structure.dimensionId())) {
            return false;
        }
        double distance = horizontalDistanceToBox(playerPos, structure.boundingBox());
        return distance <= ActionTimelineRuntimeSettings.getMajorStructureExitRadiusBlocks();
    }

    private TrackedStructure detectNearestMajorStructure(
            Level level,
            BlockPos playerPos,
            String dimensionId,
            int radiusBlocks) {
        if (radiusBlocks < 0) {
            return null;
        }

        TrackedStructure localBest = findNearestMajorStructure(level, playerPos, dimensionId, radiusBlocks);
        if (localBest != null || !level.isClientSide) {
            return localBest;
        }

        ServerLevel serverLevel = resolveIntegratedServerLevel(level);
        if (serverLevel == null) {
            return null;
        }
        return findNearestMajorStructure(serverLevel, playerPos, dimensionId, radiusBlocks);
    }

    private TrackedStructure findNearestMajorStructure(
            Level level,
            BlockPos playerPos,
            String dimensionId,
            int radiusBlocks) {
        int scanRadiusChunks = Math.max(
                MIN_SCAN_RADIUS_CHUNKS,
                (int) Math.ceil((radiusBlocks + STRUCTURE_SCAN_MARGIN_BLOCKS) / 16.0));
        int centerCX = SectionPos.blockToSectionCoord(playerPos.getX());
        int centerCZ = SectionPos.blockToSectionCoord(playerPos.getZ());

        TrackedStructure best = null;
        for (int dx = -scanRadiusChunks; dx <= scanRadiusChunks; dx++) {
            for (int dz = -scanRadiusChunks; dz <= scanRadiusChunks; dz++) {
                int cx = centerCX + dx;
                int cz = centerCZ + dz;

                ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, false);
                if (chunk == null) {
                    continue;
                }

                for (var entry : chunk.getAllStarts().entrySet()) {
                    Structure structure = entry.getKey();
                    StructureStart start = entry.getValue();
                    if (structure == null || start == null || !start.isValid()) {
                        continue;
                    }

                    String structureId = resolveStructureId(level, structure);
                    if (!ActionTimelineRuntimeSettings.isMajorStructureWhitelisted(structureId)) {
                        continue;
                    }

                    BoundingBox box = start.getBoundingBox();
                    if (box == null) {
                        continue;
                    }

                    double distance = horizontalDistanceToBox(playerPos, box);
                    if (distance > radiusBlocks) {
                        continue;
                    }

                    if (best == null || distance < best.distanceToPlayer()) {
                        best = TrackedStructure.from(structureId, dimensionId, box, distance);
                    }
                }
            }
        }

        return best;
    }

    private ActionRecord buildBoundaryRecord(ActionType type, TrackedStructure structure, boolean entering) {
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.nativeSpecific(structure.structureId()));
        tags.add(DetailTag.nativeSpecific(structure.dimensionId()));
        tags.add(DetailTag.customGeneric("major_structure"));
        tags.add(DetailTag.customGeneric(entering ? "structure_enter" : "structure_exit"));
        return ActionRecord.of(type, structure.structureId(), tags);
    }

    private ServerLevel resolveIntegratedServerLevel(Level level) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                return null;
            }
            return server.getLevel(level.dimension());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveStructureId(Level level, Structure structure) {
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            ResourceLocation id = registry.getKey(structure);
            return id != null ? id.toString() : structure.toString();
        } catch (Exception e) {
            return structure.toString();
        }
    }

    private static double horizontalDistanceToBox(BlockPos pos, BoundingBox box) {
        int dx = 0;
        if (pos.getX() < box.minX()) {
            dx = box.minX() - pos.getX();
        } else if (pos.getX() > box.maxX()) {
            dx = pos.getX() - box.maxX();
        }

        int dz = 0;
        if (pos.getZ() < box.minZ()) {
            dz = box.minZ() - pos.getZ();
        } else if (pos.getZ() > box.maxZ()) {
            dz = pos.getZ() - box.maxZ();
        }

        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    private record TrackedStructure(
            String structureId,
            String dimensionId,
            BoundingBox boundingBox,
            double distanceToPlayer) {

        private static TrackedStructure from(
                String structureId,
                String dimensionId,
                BoundingBox box,
                double distanceToPlayer) {
            BoundingBox copy = new BoundingBox(
                    box.minX(),
                    box.minY(),
                    box.minZ(),
                    box.maxX(),
                    box.maxY(),
                    box.maxZ());
            return new TrackedStructure(structureId, dimensionId, copy, distanceToPlayer);
        }
    }
}
