package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRecord

internal class MutableTraversalState(
    val frontier: MutableList<TraversalFrontierEntry>,
    val visited: MutableSet<RelationEndpointFingerprint>,
    var pending: TraversalPendingState,
) {
    /**
     * Proof transition: `MutableTraversalState -> TraversalWorkAvailability`.
     *
     * Establishes either exhausted state or the exact pending/lowest deterministic frontier item
     * without mutating the checkpoint. No raw state escapes the pure engine.
     */
    fun peek(): TraversalWorkAvailability = when (val pendingState = pending) {
        is TraversalPendingState.Active -> TraversalWorkAvailability.Ready(
            pendingState.read.entry,
            OneHopRelationPosition.Resume(pendingState.read.relationContinuation),
        )
        TraversalPendingState.None -> if (frontier.isEmpty()) {
            TraversalWorkAvailability.Exhausted
        } else {
            TraversalWorkAvailability.Ready(frontier.first(), OneHopRelationPosition.Start)
        }
    }

    /**
     * Proof transition: `(MutableTraversalState, TraversalWorkAvailability.Ready) ->
     * TraversalFrontierEntry`.
     *
     * Establishes one cycle-marked first expansion or retains one already-visited pending read.
     * Raw queue mutation remains inside the pure engine.
     */
    fun begin(work: TraversalWorkAvailability.Ready): TraversalFrontierEntry =
        when (work.position) {
            is OneHopRelationPosition.Resume -> work.entry
            OneHopRelationPosition.Start -> frontier.removeAt(0).also { entry ->
                visited += entry.node.fingerprint
            }
        }

    /**
     * Proof transition: `(MutableTraversalState, TraversalNode) -> FrontierAdmission`.
     *
     * Establishes that only an exact node absent from both visited and queued identities may enter
     * the frontier. [FrontierAdmission.Skip] is the closed duplicate/cycle outcome.
     */
    fun frontierAdmission(node: TraversalNode): FrontierAdmission =
        if (
            node.fingerprint in visited ||
            frontier.any { it.node.fingerprint == node.fingerprint }
        ) FrontierAdmission.Skip else FrontierAdmission.Admit

    companion object {
        fun from(checkpoint: TraversalCheckpoint): MutableTraversalState = MutableTraversalState(
            checkpoint.frontier.toMutableList(),
            checkpoint.visited.toMutableSet(),
            checkpoint.pending,
        )
    }
}

internal sealed interface TraversalWorkAvailability {
    data object Exhausted : TraversalWorkAvailability

    data class Ready(
        val entry: TraversalFrontierEntry,
        val position: OneHopRelationPosition,
    ) : TraversalWorkAvailability
}

internal sealed interface TraversalReadAdmission {
    data object Admitted : TraversalReadAdmission
    data class Limited(val limitation: TraversalLimitation) : TraversalReadAdmission
}

internal enum class ReaderBatchAdmission {
    Accepted,
    Rejected,
}

internal enum class FrontierAdmission {
    Admit,
    Skip,
}

internal class TraversalAccounting(
    val records: MutableList<TraversalRecord> = mutableListOf(),
    var encodedBytes: Long = 0L,
    var examinedWorkUnits: Long = 0L,
    var elapsedMillis: Long = 0L,
    var expandedFrontier: Int = 0,
) {
    /**
     * Proof transition: `(TraversalAccounting, TraversalPlan) -> Refinement<TraversalPage,
     * TraversalPageFailure>`.
     *
     * Establishes exact deterministic aggregate measures under every plan bound.
     * [io.github.amichne.kast.traversal.contract.TraversalPageFailure] is the closed expected
     * failure. Raw counters are extracted only at this pure page-construction boundary.
     */
    fun page(plan: TraversalPlan) = TraversalPage.fromBoundary(
        plan,
        records.sorted(),
        encodedBytes,
        examinedWorkUnits,
        elapsedMillis,
        expandedFrontier,
    )
}
