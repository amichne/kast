package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPartialExpansion
import io.github.amichne.kast.traversal.contract.TraversalPendingRead
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy

/** Pure deterministic breadth-first implementation of public `traversal.run`. */
class TraversalService internal constructor(private val reader: OneHopRelationReader) : TraversalOperations {
    override suspend fun run(plan: TraversalPlan): TraversalResult {
        val checkpoint =
            when (val position = plan.position) {
                TraversalPosition.Start -> TraversalCheckpoint.initial(plan)
                is TraversalPosition.Resume -> position.continuation.checkpoint
            }
        if (checkpoint.identity != plan.identity) {
            return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }
        val state = MutableTraversalState.from(checkpoint)
        val accounting = TraversalAccounting()

        while (true) {
            val work =
                when (val available = state.peek()) {
                    TraversalWorkAvailability.Exhausted ->
                        return if (state.terminalRelationLimitations.isEmpty()) {
                            complete(plan, accounting)
                        } else {
                            terminalIncomplete(
                                plan,
                                accounting,
                                setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
                                state.terminalRelationLimitations,
                            )
                        }
                    is TraversalWorkAvailability.Ready -> available
                }
            val readBudget =
                when (val admission = admitTraversalRead(plan, work.entry, accounting)) {
                    is TraversalReadAdmission.Admitted -> admission.budget
                    is TraversalReadAdmission.Limited ->
                        return if (admission.limitation == TraversalLimitation.DEPTH_LIMIT_REACHED) {
                            terminalIncomplete(
                                plan,
                                accounting,
                                state.limitationsWith(admission.limitation),
                                state.terminalRelationLimitations,
                            )
                        } else {
                            resumable(
                                plan,
                                state,
                                accounting,
                                state.limitationsWith(admission.limitation),
                                state.terminalRelationLimitations,
                            )
                        }
                    TraversalReadAdmission.Rejected ->
                        return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
                }

            val entry = state.begin(work)
            val request =
                OneHopRelationRequest(
                    node = entry.node,
                    meaning = plan.meaning,
                    scope = plan.scope,
                    budget = readBudget,
                    position = work.position,
                )
            val read =
                when (val outcome = reader.read(request)) {
                    is OneHopRelationRead.Completed -> outcome
                    OneHopRelationRead.Rejected ->
                        return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
                }
            accounting.expandedFrontier += 1
            val timeOverrun = read.elapsedMillis.value > request.budget.resources.elapsedTimeLimit.value
            val relationResult = read.result
            if (relationResult is RelationReadResult.Rejected) {
                return TraversalResult.Rejected(TraversalRejection.OneHopRejected(relationResult.reason))
            }
            val batch =
                when (relationResult) {
                    is RelationReadResult.Complete -> relationResult.batch
                    is RelationReadResult.Qualified -> relationResult.batch
                    is RelationReadResult.Rejected ->
                        return TraversalResult.Rejected(TraversalRejection.OneHopRejected(relationResult.reason))
                }
            if (admitTraversalBatch(request, batch) == ReaderBatchAdmission.Rejected) {
                return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
            }
            val nextDepth =
                when (val next = entry.depth.next()) {
                    is Refinement.Refined -> next.value
                    is Refinement.Rejected ->
                        return terminalIncomplete(
                            plan,
                            accounting,
                            state.limitationsWith(TraversalLimitation.DEPTH_LIMIT_REACHED),
                            state.terminalRelationLimitations,
                        )
                }
            val records = mutableListOf<TraversalRecord>()
            for (fact in batch.facts) {
                val record =
                    when (
                        val projected =
                            TraversalRecord.create(
                                plan,
                                entry.node.fingerprint,
                                nextDepth,
                                fact,
                            )
                    ) {
                        is Refinement.Refined -> projected.value
                        is Refinement.Rejected ->
                            return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
                    }
                records += record
            }
            accounting.records += records
            accounting.encodedBytes += batch.encodedBytes.value
            accounting.examinedWorkUnits += batch.examinedWorkUnits.value
            accounting.elapsedMillis += read.elapsedMillis.value
            for (record in records) {
                val node =
                    when (val related = TraversalNode.related(plan, record.related)) {
                        is Refinement.Refined -> related.value
                        is Refinement.Rejected ->
                            return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
                    }
                when (state.frontierAdmission(node)) {
                    FrontierAdmission.Skip -> Unit
                    FrontierAdmission.Admit -> {
                        val frontier =
                            when (val admitted = TraversalFrontierEntry.create(plan, node, nextDepth)) {
                                is Refinement.Refined -> admitted.value
                                is Refinement.Rejected ->
                                    return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
                            }
                        state.frontier += frontier
                    }
                }
            }
            state.frontier.sort()

            when (relationResult) {
                is RelationReadResult.Complete -> state.pending = TraversalPendingState.None
                is RelationReadResult.Qualified -> {
                    val coverage = relationResult.coverage
                    val remainder =
                        if (
                            coverage is RelationIncompleteCoverage.TerminalIncomplete ||
                                plan.strategy is TraversalStrategy.BoundedFanOut
                        )
                            TraversalExpansionRemainder.NOT_EXPLORED
                        else TraversalExpansionRemainder.CONTINUATION_RETAINED
                    when (
                        val partial =
                            TraversalPartialExpansion.create(
                                plan,
                                entry,
                                coverage.limitations,
                                remainder,
                            )
                    ) {
                        is Refinement.Refined -> accounting.partialExpansions += partial.value
                        is Refinement.Rejected ->
                            return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
                    }

                    if (
                        coverage is RelationIncompleteCoverage.TerminalIncomplete ||
                            plan.strategy is TraversalStrategy.BoundedFanOut
                    ) {
                        state.pending = TraversalPendingState.None
                        state.terminalRelationLimitations += coverage.limitations
                    } else {
                        val continuation = (coverage as RelationIncompleteCoverage.Resumable).continuation
                        val pending =
                            when (
                                val pending =
                                    TraversalPendingRead.create(
                                        plan,
                                        entry,
                                        continuation,
                                    )
                            ) {
                                is Refinement.Refined -> pending.value
                                is Refinement.Rejected ->
                                    return TraversalResult.Rejected(TraversalRejection.ReaderContractViolation)
                            }
                        state.pending = TraversalPendingState.active(pending)
                        return resumable(
                            plan = plan,
                            state = state,
                            accounting = accounting,
                            limitations =
                                setOf(TraversalLimitation.ONE_HOP_INCOMPLETE) +
                                    if (timeOverrun) setOf(TraversalLimitation.TIME_LIMIT_REACHED) else emptySet(),
                            relationLimitations =
                                state.terminalRelationLimitations + relationResult.coverage.limitations,
                        )
                    }
                }
                is RelationReadResult.Rejected ->
                    return TraversalResult.Rejected(TraversalRejection.OneHopRejected(relationResult.reason))
            }
            if (timeOverrun) return timeLimited(plan, state, accounting)
        }
    }

    private fun timeLimited(
        plan: TraversalPlan,
        state: MutableTraversalState,
        accounting: TraversalAccounting,
    ): TraversalResult {
        val limitations = state.limitationsWith(TraversalLimitation.TIME_LIMIT_REACHED)
        return when (state.peek()) {
            TraversalWorkAvailability.Exhausted ->
                terminalIncomplete(
                    plan = plan,
                    accounting = accounting,
                    limitations = limitations,
                    relationLimitations = state.terminalRelationLimitations,
                )
            is TraversalWorkAvailability.Ready ->
                resumable(
                    plan = plan,
                    state = state,
                    accounting = accounting,
                    limitations = limitations,
                    relationLimitations = state.terminalRelationLimitations,
                )
        }
    }

    /**
     * Proof transition: `(TraversalPlan, exhausted engine state, accounting) -> TraversalResult`.
     *
     * Establishes deterministic frontier exhaustion under complete one-hop coverage. Any internal page inconsistency
     * closes as [TraversalRejection.TraversalContractViolation].
     */
    private fun complete(
        plan: TraversalPlan,
        accounting: TraversalAccounting,
    ): TraversalResult =
        when (val page = accounting.page(plan)) {
            is Refinement.Refined -> TraversalResult.complete(page.value)
            is Refinement.Rejected -> TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }

    /**
     * Proof transition: `(TraversalPlan, stopped engine state, accounting, limitation) -> TraversalResult`.
     *
     * Establishes deterministic resumable partial evidence with non-empty closed limitations. Checkpoint, continuation,
     * page, or qualification rejection closes as [TraversalRejection.TraversalContractViolation].
     */
    private fun resumable(
        plan: TraversalPlan,
        state: MutableTraversalState,
        accounting: TraversalAccounting,
        limitations: Set<TraversalLimitation>,
        relationLimitations: Set<RelationLimitation>,
    ): TraversalResult {
        val page =
            when (val admitted = accounting.page(plan)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
            }
        if (accounting.expandedFrontier == 0) {
            return terminalIncomplete(
                plan,
                accounting,
                limitations + TraversalLimitation.NO_PROGRESS,
                relationLimitations,
            )
        }
        val checkpoint =
            when (
                val admitted =
                    TraversalCheckpoint.create(
                        plan,
                        state.frontier.sorted(),
                        state.visited,
                        state.pending,
                        state.terminalRelationLimitations,
                        page.progress,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
            }
        val continuation =
            when (val issued = TraversalContinuation.issue(plan, checkpoint)) {
                is Refinement.Refined -> issued.value
                is Refinement.Rejected -> return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
            }
        return when (
            val result =
                TraversalResult.qualifiedResumable(
                    page,
                    limitations,
                    relationLimitations,
                    continuation,
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }
    }

    /** Terminal one-hop incompleteness is explicit and cannot manufacture resumable work. */
    private fun terminalIncomplete(
        plan: TraversalPlan,
        accounting: TraversalAccounting,
        limitations: Set<TraversalLimitation>,
        relationLimitations: Set<RelationLimitation>,
    ): TraversalResult {
        val page =
            when (val admitted = accounting.page(plan)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
            }
        return when (
            val result =
                TraversalResult.qualifiedTerminal(
                    page,
                    limitations,
                    relationLimitations,
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }
    }
}

private fun MutableTraversalState.limitationsWith(limitation: TraversalLimitation): Set<TraversalLimitation> =
    buildSet {
        add(limitation)
        if (terminalRelationLimitations.isNotEmpty()) {
            add(TraversalLimitation.ONE_HOP_INCOMPLETE)
        }
    }
