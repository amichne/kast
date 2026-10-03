package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryImpactPeerElapsedNanos
import io.github.amichne.kast.query.contract.QueryImpactPeerProofFailure
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.query.contract.QueryImpactRetainedGraph
import io.github.amichne.kast.query.protocol.QueryImpactPeerSelection
import io.github.amichne.kast.query.protocol.ReadReacquisitionBudgetFailure
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import java.util.Collections

internal sealed interface HostedPeerSiteAdmissions {
    class Admitted(values: List<QueryImpactPeerSiteAdmission>) : HostedPeerSiteAdmissions {
        val values: List<QueryImpactPeerSiteAdmission> = Collections.unmodifiableList(values.toList())
    }

    data class HostedRejected(val response: HostedResponse.ReadRejected) : HostedPeerSiteAdmissions

    data class QueryRejected(val cause: QueryRunRejection) : HostedPeerSiteAdmissions

    data class ProofRejected(val cause: QueryImpactPeerProofFailure, val position: ProtocolOffset) :
        HostedPeerSiteAdmissions
}

/** Child reads consume the source's original accounting owner; no continuation or semantic store is created here. */
internal suspend fun acquireHostedPeerSites(
    selections: List<QueryImpactPeerSelection>,
    budget: QueryBudget,
    accounting: ReadAcquisitionAccounting,
    read: HostedPeerSiteReadPort = nativeHostedPeerSiteReads,
    observation: IntellijReadObservation = IntellijReadObservation.None,
    clock: () -> Long = System::nanoTime,
): HostedPeerSiteAdmissions {
    val admissions = mutableListOf<QueryImpactPeerSiteAdmission>()
    val graph = QueryImpactRetainedGraph()
    var retainedBytes = 0L
    for (selection in selections) {
        val grant =
            when (val admitted = peerGrant(budget, accounting, retainedBytes, selection.modelPosition)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return HostedPeerSiteAdmissions.QueryRejected(admitted.failure)
            }
        val completed =
            observedPeerRead(selection, grant, read, observation, clock) { admission ->
                val receipt = admission.acquisition
                accounting.record(receipt.examinedWorkUnits.value, receipt.elapsed.value)
                retainedBytes = saturatedPeerBytes(retainedBytes, graph.peerSiteAdmission(admission))
                if (retainedBytes > budget.checkpointBytes.value)
                    Refinement.Rejected(
                        peerBudgetFailure(
                            QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED,
                            selection.modelPosition,
                        )
                    )
                else Refinement.Refined(Unit)
            }
        when (completed) {
            is HostedPeerSiteCompletion.HostedRejected ->
                return HostedPeerSiteAdmissions.HostedRejected(completed.response)
            is HostedPeerSiteCompletion.QueryRejected -> return HostedPeerSiteAdmissions.QueryRejected(completed.cause)
            is HostedPeerSiteCompletion.ProofRejected ->
                return HostedPeerSiteAdmissions.ProofRejected(
                    completed.cause,
                    selection.modelPosition,
                )
            is HostedPeerSiteCompletion.Admitted -> {
                admissions += completed.admission
            }
        }
    }
    return HostedPeerSiteAdmissions.Admitted(admissions)
}

private fun peerGrant(
    budget: QueryBudget,
    accounting: ReadAcquisitionAccounting,
    retainedBytes: Long,
    position: ProtocolOffset,
): Refinement<RelationBudget, QueryRunRejection> {
    val resources =
        when (val remaining = accounting.remaining(budget.resources)) {
            is Refinement.Refined -> remaining.value
            is Refinement.Rejected ->
                return Refinement.Rejected(
                    peerBudgetFailure(
                        when (remaining.failure) {
                            ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED ->
                                QueryImpactSourceFailureCode.WORK_LIMIT_REACHED
                            ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED ->
                                QueryImpactSourceFailureCode.TIME_LIMIT_REACHED
                        },
                        position,
                    )
                )
        }
    val bytes =
        when (val remaining = RelationByteLimit.parse(budget.checkpointBytes.value - retainedBytes)) {
            is Refinement.Refined -> remaining.value
            is Refinement.Rejected ->
                return Refinement.Rejected(
                    peerBudgetFailure(
                        QueryImpactSourceFailureCode.BYTE_LIMIT_REACHED,
                        position,
                    )
                )
        }
    return Refinement.Refined(RelationBudget(resources, bytes))
}

private suspend fun observedPeerRead(
    selection: QueryImpactPeerSelection,
    grant: RelationBudget,
    read: HostedPeerSiteReadPort,
    observation: IntellijReadObservation,
    clock: () -> Long,
    accept: (QueryImpactPeerSiteAdmission) -> Refinement<Unit, QueryRunRejection>,
): HostedPeerSiteCompletion {
    observation.phase(IntellijReadPhase.PEER_SITE_ADMISSION)
    observation.count(IntellijReadCounter.PEER_SITE_READS_STARTED)
    var outcome = IntellijReadCounter.PEER_SITE_READS_REJECTED
    try {
        val started = clock()
        val result = read.read(selection, grant)
        val completed = completeHostedPeerSite(result, grant) { QueryImpactPeerElapsedNanos.parse(clock() - started) }
        if (completed !is HostedPeerSiteCompletion.Admitted) return completed
        return when (val accepted = accept(completed.admission)) {
            is Refinement.Rejected -> HostedPeerSiteCompletion.QueryRejected(accepted.failure)
            is Refinement.Refined -> {
                outcome = IntellijReadCounter.PEER_SITE_READS_COMPLETED
                completed
            }
        }
    } finally {
        observation.phase(IntellijReadPhase.PEER_SITE_ADMISSION)
        observation.count(outcome)
    }
}

private fun peerBudgetFailure(cause: QueryImpactSourceFailureCode, position: ProtocolOffset) =
    QueryRunRejection.ImpactSourceRejected(QueryImpactSourceFailureDocument.Admission(cause, position))

private fun saturatedPeerBytes(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
