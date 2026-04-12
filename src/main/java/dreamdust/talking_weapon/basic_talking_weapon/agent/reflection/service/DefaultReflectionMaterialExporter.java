package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemorySourceMaterialSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedSegmentStateSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeRegion;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionEpisodeStateSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionInteraction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterialRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionPlayerAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawSegmentMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionSegmentStateSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * 第一版 raw material exporter。
 * <p>
 * 当前直接走 owner-side source material snapshot，
 * 避免 reflection 自己重复推导 segment range / STM window。
 */
public final class DefaultReflectionMaterialExporter implements ReflectionMaterialExporter {

    @Override
    public ReflectionRawMaterial export(ReflectionMaterialRequest request, AgentMemory memory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(memory, "memory");

        MemorySourceMaterialSnapshot snapshot = memory.snapshotSourceMaterial(request.toSourceMaterialWindow());
        if (snapshot.stale()) {
            throw new IllegalStateException(
                    "Failed to resolve active source material for reflection export"
                            + " regionId=" + request.regionId()
                            + " startSegmentId=" + request.startSegmentId()
                            + " endSegmentId=" + request.endSegmentId());
        }

        List<FixedActionSegment> segments = snapshot.segments();
        STMPool.TimeSlice stmSlice = snapshot.stmSlice();

        List<ReflectionRawSegmentMaterial> rawSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            rawSegments.add(toRawSegment(
                    i,
                    segment,
                    stmSlice));
        }

        return new ReflectionRawMaterial(
                request.scope(),
                request.regionId(),
                request.startSegmentId(),
                request.endSegmentId(),
                request.leftBoundaryLeftSegmentId(),
                request.leftBoundaryRightSegmentId(),
                request.rightBoundaryLeftSegmentId(),
                request.rightBoundaryRightSegmentId(),
                request.startTimestamp(),
                request.endTimestamp(),
                request.stmSliceStartTimestamp(),
                request.stmSliceExclusiveTimestamp(),
                resolveEpisodeCloseStateSnapshot(memory, request.regionId()),
                rawSegments);
    }

    private ReflectionRawSegmentMaterial toRawSegment(
            int localSegmentIndex,
            FixedActionSegment segment,
            STMPool.TimeSlice stmSlice) {
        FixedActionSegmentContext context = segment.getContext();
        ReflectionContextSnapshot segmentSnapshot = new ReflectionContextSnapshot(
                context.getEffectiveVisibleContextEntries(),
                context.getEffectiveContextExtras());
        FixedSegmentStateSnapshot closeStateSnapshot = context.getSegmentCloseStateSnapshot();
        List<TaggedContextEntry> localContextEntries = bucketByTimestamp(
                stmSlice == null ? List.of() : stmSlice.getContextEntries(),
                segment,
                TaggedContextEntry::getTimestamp);

        List<ReflectionPlayerAction> playerActions = new ArrayList<>();
        for (FixedActionSegmentEntry entry : segment.getEntries()) {
            if (entry == null || entry.getType() == null) {
                continue;
            }
            playerActions.add(new ReflectionPlayerAction(
                    Math.max(segment.getStartTimestamp(), entry.getFirstTimestamp()),
                    entry.getType(),
                    entry.getTarget(),
                    entry.getCount(),
                    entry.getDetailTags()));
        }

        List<ReflectionInteraction> interactions = new ArrayList<>();
        for (InteractionTurn turn : bucketByTimestamp(
                stmSlice == null ? List.of() : stmSlice.getInteractionTurns(),
                segment,
                InteractionTurn::getTimestamp)) {
            if (turn == null) {
                continue;
            }
            interactions.add(new ReflectionInteraction(
                    turn.getTimestamp(),
                    turn.getTriggerSource(),
                    turn.getMessages(),
                    new ReflectionContextSnapshot(turn.getVisibleContexts(), turn.getContextExtras())));
        }

        return new ReflectionRawSegmentMaterial(
                localSegmentIndex,
                segment.getSegmentId(),
                segment.getStartTimestamp(),
                segment.getEndTimestamp(),
                segmentSnapshot,
                new ReflectionSegmentStateSnapshot(
                        closeStateSnapshot.primaryEmotion(),
                        closeStateSnapshot.emotionIntensity(),
                        closeStateSnapshot.favorability()),
                localContextEntries,
                playerActions,
                interactions);
    }

    private ReflectionEpisodeStateSnapshot resolveEpisodeCloseStateSnapshot(AgentMemory memory, long regionId) {
        if (memory == null || regionId <= 0L) {
            return ReflectionEpisodeStateSnapshot.empty();
        }
        for (EpisodeRegion region : memory.getEpisodeTimelineStore().snapshot().regions()) {
            if (region == null || region.regionId() != regionId) {
                continue;
            }
            return new ReflectionEpisodeStateSnapshot(
                    region.closeStateSnapshot().playerProfile(),
                    region.closeStateSnapshot().recap());
        }
        return ReflectionEpisodeStateSnapshot.empty();
    }

    private <T> List<T> bucketByTimestamp(
            List<T> records,
            FixedActionSegment segment,
            ToLongFunction<T> timestampReader) {
        if (segment == null || records == null || records.isEmpty() || timestampReader == null) {
            return List.of();
        }
        List<T> bucket = new ArrayList<>();
        for (T record : records) {
            if (record == null) {
                continue;
            }
            long timestamp = timestampReader.applyAsLong(record);
            if (timestamp >= segment.getStartTimestamp() && timestamp < segment.getEndTimestamp()) {
                bucket.add(record);
            }
        }
        return List.copyOf(bucket);
    }
}
