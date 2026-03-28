package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawSegmentMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentEvent;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentEventType;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 第一版 material merger。
 * <p>
 * 将 raw segment 中的 snapshot / player action / interaction 合并成稳定事件流。
 */
public final class DefaultReflectionMaterialMerger implements ReflectionMaterialMerger {

    private static final Comparator<ReflectionSegmentEvent> EVENT_ORDER =
            Comparator.comparingLong(ReflectionSegmentEvent::timestamp)
                    .thenComparingInt(event -> switch (event.type()) {
                        case SEGMENT_START_SNAPSHOT -> 0;
                        case PLAYER_ACTION -> 1;
                        case AGENT_INTERACTION -> 2;
                    });

    @Override
    public ReflectionMaterial merge(ReflectionRawMaterial rawMaterial) {
        Objects.requireNonNull(rawMaterial, "rawMaterial");

        List<ReflectionSegmentMaterial> mergedSegments = new ArrayList<>();
        for (ReflectionRawSegmentMaterial rawSegment : rawMaterial.segments()) {
            if (rawSegment == null) {
                continue;
            }
            List<ReflectionSegmentEvent> timelineEvents = new ArrayList<>();
            timelineEvents.add(new ReflectionSegmentEvent(
                    ReflectionSegmentEventType.SEGMENT_START_SNAPSHOT,
                    rawSegment.startTimestamp(),
                    rawSegment.startSnapshot(),
                    null,
                    null));
            rawSegment.playerActions().forEach(action -> timelineEvents.add(new ReflectionSegmentEvent(
                    ReflectionSegmentEventType.PLAYER_ACTION,
                    action.timestamp(),
                    null,
                    action,
                    null)));
            rawSegment.interactions().forEach(interaction -> timelineEvents.add(new ReflectionSegmentEvent(
                    ReflectionSegmentEventType.AGENT_INTERACTION,
                    interaction.timestamp(),
                    interaction.boundSnapshot(),
                    null,
                    interaction)));
            timelineEvents.sort(EVENT_ORDER);

            mergedSegments.add(new ReflectionSegmentMaterial(
                    rawSegment.localSegmentIndex(),
                    rawSegment.timelineSegmentIndex(),
                    rawSegment.segmentId(),
                    rawSegment.startTimestamp(),
                    rawSegment.endTimestamp(),
                    rawSegment.startSnapshot(),
                    rawSegment.playerActions(),
                    rawSegment.interactions(),
                    timelineEvents));
        }

        return new ReflectionMaterial(
                rawMaterial.scope(),
                rawMaterial.startTimelineSegmentIndex(),
                rawMaterial.startSegmentId(),
                rawMaterial.endTimelineSegmentIndex(),
                rawMaterial.endSegmentId(),
                rawMaterial.leftBoundarySplitSegmentIndex(),
                rawMaterial.leftBoundaryLeftSegmentId(),
                rawMaterial.leftBoundaryRightSegmentId(),
                rawMaterial.rightBoundarySplitSegmentIndex(),
                rawMaterial.rightBoundaryLeftSegmentId(),
                rawMaterial.rightBoundaryRightSegmentId(),
                rawMaterial.startTimestamp(),
                rawMaterial.endTimestamp(),
                rawMaterial.interactionSliceExclusiveTimestamp(),
                mergedSegments);
    }
}
