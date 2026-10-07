package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.traversal.contract.TraversalCallableObservation
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPageFailure
import io.github.amichne.kast.traversal.contract.TraversalPartialExpansion
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPosition
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalRejection
import java.util.PriorityQueue

internal class MutableTraversalState
private constructor(
    frontier: List<TraversalFrontierEntry>,
    val visited: MutableSet<RelationEndpointFingerprint>,
    var pending: TraversalPendingState,
    val terminalRelationLimitations: MutableSet<RelationLimitation>,
    val retainedOmissions: MutableList<io.github.amichne.kast.traversal.contract.TraversalPartialExpansion>,
) {
    private val frontier = PriorityQueue(frontier)
    private val queued = frontier.mapTo(hashSetOf()) { it.node.fingerprint }

    fun frontierSnapshot(): List<TraversalFrontierEntry> = frontier.sorted()

    /** Admits each exact node once while retaining deterministic depth/fingerprint expansion order. */
    fun enqueue(entry: TraversalFrontierEntry) {
        when (frontierAdmission(entry.node)) {
            FrontierAdmission.Skip -> Unit
            FrontierAdmission.Admit -> {
                frontier += entry
                queued += entry.node.fingerprint
            }
        }
    }

    /**
     * Proof transition: `MutableTraversalState -> TraversalWorkAvailability`.
     *
     * Establishes either exhausted state or the exact pending/lowest deterministic frontier item without mutating the
     * checkpoint. No raw state escapes the pure engine.
     */
    fun peek(): TraversalWorkAvailability =
        when (val pendingState = pending) {
            is TraversalPendingState.Active ->
                TraversalWorkAvailability.Ready(
                    pendingState.read.entry,
                    OneHopRelationPosition.Resume(pendingState.read.relationContinuation),
                )
            TraversalPendingState.None ->
                if (frontier.isEmpty()) {
                    TraversalWorkAvailability.Exhausted
                } else {
                    TraversalWorkAvailability.Ready(frontier.element(), OneHopRelationPosition.Start)
                }
        }

    /**
     * Proof transition: `(MutableTraversalState, TraversalWorkAvailability.Ready) -> TraversalFrontierEntry`.
     *
     * Establishes one cycle-marked first expansion or retains one already-visited pending read. Raw queue mutation
     * remains inside the pure engine.
     */
    fun begin(work: TraversalWorkAvailability.Ready): TraversalFrontierEntry =
        when (work.position) {
            is OneHopRelationPosition.Resume -> work.entry
            OneHopRelationPosition.Start ->
                frontier.remove().also { entry ->
                    queued -= entry.node.fingerprint
                    visited += entry.node.fingerprint
                }
        }

    /**
     * Proof transition: `(MutableTraversalState, TraversalNode) -> FrontierAdmission`.
     *
     * Establishes that only an exact node absent from both visited and queued identities may enter the frontier.
     * [FrontierAdmission.Skip] is the closed duplicate/cycle outcome.
     */
    private fun frontierAdmission(node: TraversalNode): FrontierAdmission =
        if (node.fingerprint in visited || node.fingerprint in queued) FrontierAdmission.Skip
        else FrontierAdmission.Admit

    /** Repeated inherited qualifications carry no new measurement; observed page evidence remains distinct. */
    fun retainOmissions(partial: TraversalPartialExpansion) {
        val retained = partial.retainedExpansions()
        retainedOmissions += retained.filterNot { expansion ->
            expansion in retainedOmissions &&
                expansion.omissions.all {
                    it.measurement == RelationOmissionMeasurement.UnmeasuredOnPage && it.samples.locations.isEmpty()
                }
        }
        terminalRelationLimitations += retained.flatMap { it.limitations }
    }

    companion object {
        fun from(checkpoint: TraversalCheckpoint): MutableTraversalState =
            MutableTraversalState(
                checkpoint.frontier,
                checkpoint.visited.toMutableSet(),
                checkpoint.pending,
                checkpoint.terminalRelationLimitations.toMutableSet(),
                checkpoint.retainedOmissions.toMutableList(),
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
    data class Admitted(val budget: RelationBudget) : TraversalReadAdmission

    data class Limited(val limitation: TraversalLimitation) : TraversalReadAdmission

    data object Rejected : TraversalReadAdmission
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
    val partialExpansions: MutableList<io.github.amichne.kast.traversal.contract.TraversalPartialExpansion> =
        mutableListOf(),
    var encodedBytes: Long = 0L,
    var examinedWorkUnits: Long = 0L,
    var elapsedMillis: Long = 0L,
    var expandedFrontier: Int = 0,
    val inheritedOmissions: List<io.github.amichne.kast.traversal.contract.TraversalPartialExpansion> = emptyList(),
    val referenceOccurrences: MutableList<io.github.amichne.kast.traversal.contract.TraversalReferenceObservation> =
        mutableListOf(),
) {
    val scopeExclusions = mutableListOf<io.github.amichne.kast.traversal.contract.TraversalScopeExclusion>()

    val callbackObservations = mutableListOf<io.github.amichne.kast.traversal.contract.TraversalCallbackObservation>()

    val callableObservations = mutableListOf<io.github.amichne.kast.traversal.contract.TraversalCallableObservation>()

    val retainedResultCount: Int
        get() =
            semanticResultCount +
                scopeExclusions.size +
                callableObservations.size +
                callbackObservations.count { callback ->
                    records.none { it.fact.occurrence == callback.observation.occurrence }
                }

    val semanticResultCount: Int
        get() =
            records.size +
                referenceOccurrences.count { observation ->
                    records.none {
                        it.fact.occurrence == observation.reference.occurrence &&
                            it.fact.target == observation.reference.target
                    }
                }

    /** Completes page accounting only after the final detached callable proofs have been preserved. */
    fun retainReadCompletion(
        plan: TraversalPlan,
        entry: TraversalFrontierEntry,
        batch: RelationBatch,
        elapsed: OneHopElapsedMillis,
    ): Refinement<Unit, TraversalRejection> {
        for (callable in batch.callableObservations) {
            when (val observed = TraversalCallableObservation.create(plan, entry, callable)) {
                is Refinement.Refined -> callableObservations += observed.value
                is Refinement.Rejected -> return Refinement.Rejected(TraversalRejection.ReaderContractViolation)
            }
        }
        encodedBytes += batch.encodedBytes.value
        examinedWorkUnits += batch.examinedWorkUnits.value
        elapsedMillis += elapsed.value
        return Refinement.Refined(Unit)
    }

    /**
     * Proof transition: `(TraversalAccounting, TraversalPlan) -> Refinement<TraversalPage, TraversalPageFailure>`.
     *
     * Establishes exact deterministic aggregate measures under every plan bound. [TraversalPageFailure] is the closed
     * expected failure. Raw counters are extracted only at this pure page-construction boundary.
     */
    fun page(plan: TraversalPlan): Refinement<TraversalPage, TraversalPageFailure> {
        val prior =
            when (val position = plan.position) {
                TraversalPosition.Start -> TraversalProgress.Initial
                is TraversalPosition.Resume -> position.continuation.checkpoint.progress
            }
        val progress =
            when (
                val advanced =
                    prior.advance(expandedFrontier, records.size, records.maxOfOrNull { it.depth.value } ?: 0)
            ) {
                is Refinement.Refined -> advanced.value
                is Refinement.Rejected -> return Refinement.Rejected(TraversalPageFailure.NEGATIVE_MEASURE)
            }
        return TraversalPage.fromBoundary(
            plan,
            records.sorted(),
            encodedBytes,
            examinedWorkUnits,
            elapsedMillis,
            expandedFrontier,
            progress,
            partialExpansions,
            inheritedOmissions,
            referenceOccurrences.sorted(),
            scopeExclusions.sorted(),
            callbackObservations.sorted(),
            callableObservations.sorted(),
        )
    }
}
