package dreamdust.talking_weapon.basic_talking_weapon.agent.reflection.service;

import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.AgentMemory;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.InteractionTurn;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.MemorySourceMaterialSnapshot;
import dreamdust.talking_weapon.basic_talking_weapon.agent.memory.STMPool;
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
}
