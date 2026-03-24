package dreamdust.talking_weapon.basic_talking_weapon.agent.context.trigger;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.DetailTag;
import dreamdust.talking_weapon.basic_talking_weapon.agent.dictionary.LocationRegistry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionRecord;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionTimelineRuntimeSettings;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.ActionType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已登记地点的 enter / leave 边界 tracker。
 * <p>
 * enter 半径直接取 {@link LocationRegistry.LocationEntry} 上的配置，
 * leave 半径则在 enter 半径基础上额外扩张一段可调距离，形成简单滞回。
 */
public final class RegisteredLocationBoundaryTracker {

    private boolean initialized;
    private TrackedLocation currentLocation;

    public List<ActionRecord> update(Player player, Level level) {
        if (player == null || level == null) {
            return List.of();
        }

        String dimensionId = level.dimension().location().toString();
        TrackedLocation enterCandidate = resolveEnterCandidate(player, dimensionId);

        if (!initialized) {
            initialized = true;
            currentLocation = enterCandidate;
            return List.of();
        }

        ArrayList<ActionRecord> records = new ArrayList<>(2);
        if (currentLocation != null && !isWithinLeaveRange(currentLocation, player, dimensionId)) {
            records.add(buildBoundaryRecord(ActionType.LEAVE_REGISTERED_LOCATION, currentLocation, false));
            currentLocation = null;
        }

        if (currentLocation == null && enterCandidate != null) {
            currentLocation = enterCandidate;
            records.add(buildBoundaryRecord(ActionType.ENTER_REGISTERED_LOCATION, currentLocation, true));
        }

        return records.isEmpty() ? List.of() : List.copyOf(records);
    }

    private TrackedLocation resolveEnterCandidate(Player player, String dimensionId) {
        LocationRegistry.ResolvedLocation resolved = LocationRegistry.getInstance().resolveCurrentLocation(
                player.getX(),
                player.getY(),
                player.getZ(),
                dimensionId,
                LocationRegistry.DEFAULT_HORIZONTAL_RADIUS,
                LocationRegistry.DEFAULT_VERTICAL_RADIUS);
        return resolved == null ? null : TrackedLocation.fromResolved(resolved);
    }

    private boolean isWithinLeaveRange(TrackedLocation tracked, Player player, String dimensionId) {
        if (tracked == null || player == null || dimensionId == null || !dimensionId.equals(tracked.dimensionId())) {
            return false;
        }

        double dx = player.getX() - tracked.x();
        double dz = player.getZ() - tracked.z();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDistance = Math.abs(player.getY() - tracked.y());
        double leaveHorizontalRadius =
                tracked.horizontalRadius() + ActionTimelineRuntimeSettings.getRegisteredLocationLeaveExtraBlocks();

        return horizontalDistance <= leaveHorizontalRadius && verticalDistance <= tracked.verticalRadius();
    }

    private ActionRecord buildBoundaryRecord(ActionType type, TrackedLocation location, boolean entering) {
        Set<DetailTag> tags = new LinkedHashSet<>();
        tags.add(DetailTag.customGeneric("registered_location"));
        tags.add(DetailTag.customGeneric(entering ? "location_enter" : "location_leave"));
        tags.add(DetailTag.customSpecific(location.name()));
        tags.add(DetailTag.nativeSpecific(location.dimensionId()));

        for (String rawTag : location.detailTags()) {
            try {
                tags.add(DetailTag.parse(rawTag));
            } catch (IllegalArgumentException ignored) {
                // 地点附带标签来自 workspace authored 数据，单条非法时直接跳过即可。
            }
        }

        return ActionRecord.of(type, location.name(), tags);
    }

    private record TrackedLocation(
            String name,
            String dimensionId,
            double x,
            double y,
            double z,
            int horizontalRadius,
            int verticalRadius,
            List<String> detailTags) {

        private static TrackedLocation fromResolved(LocationRegistry.ResolvedLocation resolved) {
            LocationRegistry.LocationEntry entry = resolved.getEntry();
            return new TrackedLocation(
                    resolved.getName(),
                    entry.dim,
                    entry.x,
                    entry.y,
                    entry.z,
                    Math.max(1, entry.horizontalRadius),
                    Math.max(1, entry.verticalRadius),
                    List.copyOf(entry.getParsedDetailTags()));
        }
    }
}
