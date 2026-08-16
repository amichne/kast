package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPendingRead
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult

/** Pure deterministic breadth-first implementation of public `traversal.run`. */
class TraversalService internal constructor(
    private val reader: OneHopRelationReader,
) : TraversalOperations {
    override suspend fun run(plan: TraversalPlan): TraversalResult {
        val checkpoint = when (val position = plan.position) {
            TraversalPosition.Start -> TraversalCheckpoint.initial(plan)
            is TraversalPosition.Resume -> position.continuation.checkpoint
        }
        if (checkpoint.identity != plan.identity) {
            return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }
        val state = MutableTraversalState.from(checkpoint)
        val accounting = TraversalAccounting()

        while (true) {
            val work = when (val available = state.peek()) {
                TraversalWorkAvailability.Exhausted -> return complete(plan, accounting)
                is TraversalWorkAvailability.Ready -> available
            }
            when (val admission = readAdmission(plan, work.entry, accounting)) {
                TraversalReadAdmission.Admitted -> Unit
                is TraversalReadAdmission.Limited -> return qualified(
                    plan,
                    state,
                    accounting,
                    admission.limitation,
                    emptySet(),
                )
            }

            val entry = state.begin(work)
            val request = OneHopRelationRequest(
                node = entry.node,
                meaning = plan.meaning,
                scope = plan.scope,
                budget = plan.budget.oneHop,
                position = work.position,
            )
            val read = when (val outcome = reader.read(request)) {
                is OneHopRelationRead.Completed -> outcome
                OneHopRelationRead.Rejected -> return TraversalResult.Rejected(
                    TraversalRejection.ReaderContractViolation,
                )
            }
            accounting.expandedFrontier += 1
            if (read.elapsedMillis.value > plan.budget.oneHop.resources.elapsedTimeLimit.value) {
                return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
            }
            val relationResult = read.result
            if (relationResult is RelationReadResult.Rejected) {
                return TraversalResult.Rejected(
                    TraversalRejection.OneHopRejected(relationResult.reason),
                )
            }
            val batch = when (relationResult) {
                is RelationReadResult.Complete -> relationResult.batch
                is RelationReadResult.Qualified -> relationResult.batch
                is RelationReadResult.Rejected -> return TraversalResult.Rejected(
                    TraversalRejection.OneHopRejected(relationResult.reason),
                )
            }
            if (batchAdmission(request, batch) == ReaderBatchAdmission.Rejected) {
                return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
            }
            val nextDepth = when (val next = entry.depth.next()) {
                is Refinement.Refined -> next.value
                is Refinement.Rejected -> return qualified(
                    plan,
                    state,
                    accounting,
                    TraversalLimitation.DEPTH_LIMIT_REACHED,
                    emptySet(),
                )
            }
            val records = mutableListOf<TraversalRecord>()
            for (fact in batch.facts) {
                val record = when (
                    val projected = TraversalRecord.create(
                        plan,
                        entry.node.fingerprint,
                        nextDepth,
                        fact,
                    )
                ) {
                    is Refinement.Refined -> projected.value
                    is Refinement.Rejected -> return TraversalResult.Rejected(
                        TraversalRejection.ReaderContractViolation,
                    )
                }
                records += record
            }
            accounting.records += records
            accounting.encodedBytes += batch.encodedBytes.value
            accounting.examinedWorkUnits += batch.examinedWorkUnits.value
            accounting.elapsedMillis += read.elapsedMillis.value
            for (record in records) {
                val node = when (val related = TraversalNode.related(plan, record.related)) {
                    is Refinement.Refined -> related.value
                    is Refinement.Rejected -> return TraversalResult.Rejected(
                        TraversalRejection.ReaderContractViolation,
                    )
                }
                when (state.frontierAdmission(node)) {
                    FrontierAdmission.Skip -> Unit
                    FrontierAdmission.Admit -> {
                        val frontier = when (
                            val admitted = TraversalFrontierEntry.create(plan, node, nextDepth)
                        ) {
                            is Refinement.Refined -> admitted.value
                            is Refinement.Rejected -> return TraversalResult.Rejected(
                                TraversalRejection.TraversalContractViolation,
                            )
                        }
                        state.frontier += frontier
                    }
                }
            }
            state.frontier.sort()

            when (relationResult) {
                is RelationReadResult.Complete -> state.pending = TraversalPendingState.None
                is RelationReadResult.Qualified -> {
                    val pending = when (
                        val pending = TraversalPendingRead.create(
                            plan,
                            entry,
                            relationResult.coverage.continuation,
                        )
                    ) {
                        is Refinement.Refined -> pending.value
                        is Refinement.Rejected -> return TraversalResult.Rejected(
                            TraversalRejection.ReaderContractViolation,
                        )
                    }
                    state.pending = TraversalPendingState.active(pending)
                    return qualified(
                        plan,
                        state,
                        accounting,
                        TraversalLimitation.ONE_HOP_INCOMPLETE,
                        relationResult.coverage.limitations,
                    )
                }
                is RelationReadResult.Rejected -> return TraversalResult.Rejected(
                    TraversalRejection.OneHopRejected(relationResult.reason),
                )
            }
        }
    }

    /**
     * Proof transition: `(TraversalPlan, TraversalFrontierEntry, TraversalAccounting) ->
     * TraversalReadAdmission`.
     *
     * Establishes either explicit authority for one configured bounded read or one closed
     * aggregate limitation before effects occur. Raw counters remain inside the pure engine.
     */
    private fun readAdmission(
        plan: TraversalPlan,
        next: TraversalFrontierEntry,
        accounting: TraversalAccounting,
    ): TraversalReadAdmission = when {
            next.depth.value >= plan.budget.depth.value ->
                TraversalReadAdmission.Limited(TraversalLimitation.DEPTH_LIMIT_REACHED)
            accounting.expandedFrontier >= plan.budget.frontier.value ->
                TraversalReadAdmission.Limited(TraversalLimitation.FRONTIER_LIMIT_REACHED)
            plan.budget.records.value - accounting.records.size <
                plan.budget.oneHop.resources.resultLimit.value ->
                TraversalReadAdmission.Limited(TraversalLimitation.RECORD_LIMIT_REACHED)
            plan.budget.returnedBytes.value - accounting.encodedBytes <
                plan.budget.oneHop.returnedBytes.value ->
                TraversalReadAdmission.Limited(TraversalLimitation.BYTE_LIMIT_REACHED)
            plan.budget.workUnits.value - accounting.examinedWorkUnits <
                plan.budget.oneHop.resources.workUnitLimit.value ->
                TraversalReadAdmission.Limited(TraversalLimitation.WORK_LIMIT_REACHED)
            plan.budget.elapsedTime.value - accounting.elapsedMillis <
                plan.budget.oneHop.resources.elapsedTimeLimit.value ->
                TraversalReadAdmission.Limited(TraversalLimitation.TIME_LIMIT_REACHED)
            else -> TraversalReadAdmission.Admitted
        }

    /**
     * Proof transition: `(OneHopRelationRequest, RelationBatch) -> ReaderBatchAdmission`.
     *
     * Establishes exact node, lease, scope, meaning, budget, and continuation retention.
     * [ReaderBatchAdmission.Rejected] is the closed expected failure. Raw provider objects remain
     * outside the traversal core.
     */
    private fun batchAdmission(
        request: OneHopRelationRequest,
        batch: RelationBatch,
    ): ReaderBatchAdmission {
        val relationRequest = batch.request
        val responsePosition = relationRequest.position
        val positionMatches = when (val position = request.position) {
            OneHopRelationPosition.Start -> responsePosition is RelationReadPosition.Start
            is OneHopRelationPosition.Resume ->
                responsePosition is RelationReadPosition.Resume &&
                    responsePosition.continuation.fingerprint ==
                    position.continuation.fingerprint
        }
        val matches = relationRequest.subject.fingerprint == request.node.fingerprint &&
            relationRequest.subject.lease == request.node.endpoint.lease &&
            relationRequest.subject.scope == request.scope &&
            relationRequest.meaning == request.meaning &&
            relationRequest.budget == request.budget &&
            positionMatches
        return if (matches) ReaderBatchAdmission.Accepted else ReaderBatchAdmission.Rejected
    }

    /**
     * Proof transition: `(TraversalPlan, exhausted engine state, accounting) -> TraversalResult`.
     *
     * Establishes deterministic frontier exhaustion under complete one-hop coverage. Any internal
     * page inconsistency closes as [TraversalRejection.TraversalContractViolation].
     */
    private fun complete(
        plan: TraversalPlan,
        accounting: TraversalAccounting,
    ): TraversalResult = when (val page = accounting.page(plan)) {
        is Refinement.Refined -> TraversalResult.complete(page.value)
        is Refinement.Rejected ->
            TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
    }

    /**
     * Proof transition: `(TraversalPlan, stopped engine state, accounting, limitation) ->
     * TraversalResult`.
     *
     * Establishes deterministic resumable partial evidence with non-empty closed limitations.
     * Checkpoint, continuation, page, or qualification rejection closes as
     * [TraversalRejection.TraversalContractViolation].
     */
    private fun qualified(
        plan: TraversalPlan,
        state: MutableTraversalState,
        accounting: TraversalAccounting,
        limitation: TraversalLimitation,
        relationLimitations: Set<RelationLimitation>,
    ): TraversalResult {
        val checkpoint = when (val admitted = TraversalCheckpoint.create(
            plan,
            state.frontier.sorted(),
            state.visited,
            state.pending,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return TraversalResult.Rejected(
                TraversalRejection.TraversalContractViolation,
            )
        }
        val continuation = when (val issued = TraversalContinuation.issue(plan, checkpoint)) {
            is Refinement.Refined -> issued.value
            is Refinement.Rejected -> return TraversalResult.Rejected(
                TraversalRejection.TraversalContractViolation,
            )
        }
        val page = when (val admitted = accounting.page(plan)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return TraversalResult.Rejected(
                TraversalRejection.TraversalContractViolation,
            )
        }
        return when (
            val result = TraversalResult.qualified(
                page,
                setOf(limitation),
                relationLimitations,
                continuation,
            )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected ->
                TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }
    }
}
