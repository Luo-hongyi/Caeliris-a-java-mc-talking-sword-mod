package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.context.tag.TaggedContextEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.episode.EpisodeTimeline;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegment;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentContext;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.playeraction.FixedActionSegmentEntry;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionContextSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionInteraction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionMaterialRequest;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionPlayerAction;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawMaterial;
import dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.material.ReflectionRawSegmentMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 第一版 raw material exporter。
 * <p>
 * 当前仍以 episode segment 为地基，再从 STMPool 中按时间分桶取 interaction trace。
 */
public final class DefaultReflectionMaterialExporter implements ReflectionMaterialExporter {

    @Override
    public ReflectionRawMaterial export(ReflectionMaterialRequest request, AgentMemory memory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(memory, "memory");

        EpisodeTimeline timeline = memory.getEpisodeTimelineStore().snapshot();
        List<FixedActionSegment> segments = timeline.sliceSegments(
                request.startTimelineSegmentIndex(),
                request.endTimelineSegmentIndex());
        STMPool.TimeSlice stmSlice = memory.getSTMPool().snapshotBefore(request.interactionSliceExclusiveTimestamp());

        List<ReflectionRawSegmentMaterial> rawSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            rawSegments.add(toRawSegment(
                    i,
                    request.startTimelineSegmentIndex() + i,
                    segment,
                    bucketInteractions(segments, i, stmSlice, request.startTimestamp(), request.interactionSliceExclusiveTimestamp())));
        }

        return new ReflectionRawMaterial(
                request.scope(),
                request.startTimelineSegmentIndex(),
                request.startSegmentId(),
                request.endTimelineSegmentIndex(),
                request.endSegmentId(),
                request.leftBoundarySplitSegmentIndex(),
                request.leftBoundaryLeftSegmentId(),
                request.leftBoundaryRightSegmentId(),
                request.rightBoundarySplitSegmentIndex(),
                request.rightBoundaryLeftSegmentId(),
                request.rightBoundaryRightSegmentId(),
                request.startTimestamp(),
                request.endTimestamp(),
                request.interactionSliceExclusiveTimestamp(),
                rawSegments);
    }

    private ReflectionRawSegmentMaterial toRawSegment(
            int localSegmentIndex,
            int timelineSegmentIndex,
            FixedActionSegment segment,
            List<InteractionTurn> interactionTurns) {
        FixedActionSegmentContext context = segment.getContext();
        ReflectionContextSnapshot startSnapshot = new ReflectionContextSnapshot(
                context.getDefaultVisibleContextEntries(),
                context.getDefaultContextExtras());

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
        for (InteractionTurn turn : interactionTurns) {
            if (turn == null) {
                continue;
            }
            // TODO: 当前 InteractionTurn 只持久化了 visibleContexts，
            // 还没有把当时的 extra context 一起带出来。
            interactions.add(new ReflectionInteraction(
                    turn.getTimestamp(),
                    turn.getTriggerSource(),
                    turn.getUserPrompt(),
                    turn.getAgentActions(),
                    new ReflectionContextSnapshot(turn.getVisibleContexts(), Map.of())));
        }

        return new ReflectionRawSegmentMaterial(
                localSegmentIndex,
                timelineSegmentIndex,
                segment.getSegmentId(),
                segment.getStartTimestamp(),
                segment.getEndTimestamp(),
                startSnapshot,
                playerActions,
                interactions);
    }

    private List<InteractionTurn> bucketInteractions(
            List<FixedActionSegment> segments,
            int localSegmentIndex,
            STMPool.TimeSlice stmSlice,
            long rangeStart,
            long rangeEnd) {
        if (stmSlice == null || segments.isEmpty() || localSegmentIndex < 0 || localSegmentIndex >= segments.size()) {
            return List.of();
        }
        FixedActionSegment segment = segments.get(localSegmentIndex);
        if (segment == null) {
            return List.of();
        }

        List<InteractionTurn> bucket = new ArrayList<>();
        for (InteractionTurn turn : stmSlice.getInteractionTurns()) {
            if (turn == null) {
                continue;
            }
            long timestamp = turn.getTimestamp();
            if (timestamp < rangeStart || timestamp >= rangeEnd) {
                continue;
            }
            if (timestamp >= segment.getStartTimestamp() && timestamp < segment.getEndTimestamp()) {
                bucket.add(turn);
            }
        }
        return List.copyOf(bucket);
    }
}
