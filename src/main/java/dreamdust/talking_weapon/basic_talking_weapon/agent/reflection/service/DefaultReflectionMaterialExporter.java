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
        List<FixedActionSegment> segments = resolveRequiredSegmentRange(timeline, request);
        STMPool.TimeSlice stmSlice = memory.getSTMPool().snapshotBetween(
                request.stmSliceStartTimestamp(),
                request.stmSliceExclusiveTimestamp());

        List<ReflectionRawSegmentMaterial> rawSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            FixedActionSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            rawSegments.add(toRawSegment(
                    i,
                    segment,
                    bucketInteractions(segments, i, stmSlice)));
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
                rawSegments);
    }

    private ReflectionRawSegmentMaterial toRawSegment(
            int localSegmentIndex,
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
            STMPool.TimeSlice stmSlice) {
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
            if (timestamp >= segment.getStartTimestamp() && timestamp < segment.getEndTimestamp()) {
                bucket.add(turn);
            }
        }
        return List.copyOf(bucket);
    }

    private List<FixedActionSegment> resolveRequiredSegmentRange(
            EpisodeTimeline timeline,
            ReflectionMaterialRequest request) {
        if (timeline == null) {
            throw new IllegalStateException("Episode timeline is unavailable for reflection export");
        }
        int startIndex = timeline.indexOfSegmentId(request.startSegmentId());
        int endIndex = timeline.indexOfSegmentId(request.endSegmentId());
        if (startIndex < 0 || endIndex < 0 || endIndex < startIndex) {
            throw new IllegalStateException(
                    "Failed to resolve stable segment range for reflection export"
                            + " regionId=" + request.regionId()
                            + " startSegmentId=" + request.startSegmentId()
                            + " endSegmentId=" + request.endSegmentId());
        }
        return timeline.sliceSegments(startIndex, endIndex);
    }
}
