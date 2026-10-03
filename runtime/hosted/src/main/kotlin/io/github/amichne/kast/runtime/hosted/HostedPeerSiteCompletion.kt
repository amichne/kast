package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryImpactPeerAcquisitionReceipt
import io.github.amichne.kast.query.contract.QueryImpactPeerElapsedNanos
import io.github.amichne.kast.query.contract.QueryImpactPeerElapsedNanosFailure
import io.github.amichne.kast.query.contract.QueryImpactPeerProofFailure
import io.github.amichne.kast.query.contract.QueryImpactPeerSiteAdmission
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

/** Provisional callback data cannot enter canonical admission before final read completion. */
internal sealed interface HostedPeerSiteEvaluation {
    data class Selected(
        val selection: QueryImpactRequestedSite,
        val authority: SemanticReadAuthority,
        val grant: RelationBudget,
        val examinedWorkUnits: RelationWorkCount,
    ) : HostedPeerSiteEvaluation

    data class QueryRejected(val cause: QueryRunRejection) : HostedPeerSiteEvaluation

    data class HostedRejected(val response: HostedResponse.ReadRejected) : HostedPeerSiteEvaluation
}

internal sealed interface HostedPeerSiteCompletion {
    data class Admitted(val admission: QueryImpactPeerSiteAdmission) : HostedPeerSiteCompletion

    data class HostedRejected(val response: HostedResponse.ReadRejected) : HostedPeerSiteCompletion

    data class ProofRejected(val cause: QueryImpactPeerProofFailure) : HostedPeerSiteCompletion

    data class QueryRejected(val cause: QueryRunRejection) : HostedPeerSiteCompletion
}

internal fun completeHostedPeerSite(
    read: HostedSemanticReadResult<HostedPeerSiteEvaluation>,
    offered: RelationBudget,
    elapsed: () -> Refinement<QueryImpactPeerElapsedNanos, QueryImpactPeerElapsedNanosFailure>,
): HostedPeerSiteCompletion =
    when (read) {
        is HostedSemanticReadResult.Rejected ->
            HostedPeerSiteCompletion.HostedRejected(
                HostedResponse.ReadRejected(read.failure, read.stage, read.executionBudget)
            )
        is HostedSemanticReadResult.Completed ->
            when (val evidence = read.value) {
                is HostedPeerSiteEvaluation.QueryRejected -> HostedPeerSiteCompletion.QueryRejected(evidence.cause)
                is HostedPeerSiteEvaluation.HostedRejected -> HostedPeerSiteCompletion.HostedRejected(evidence.response)
                is HostedPeerSiteEvaluation.Selected -> completeGrantedPeerSelection(evidence, offered, elapsed)
            }
    }

private fun completeGrantedPeerSelection(
    evidence: HostedPeerSiteEvaluation.Selected,
    offered: RelationBudget,
    elapsed: () -> Refinement<QueryImpactPeerElapsedNanos, QueryImpactPeerElapsedNanosFailure>,
): HostedPeerSiteCompletion {
    when (val grant = peerReturnedGrant(evidence.grant, offered)) {
        is Refinement.Rejected -> return rejectedPeerBudget(grant.failure)
        is Refinement.Refined -> Unit
    }
    return when (val admitted = elapsed()) {
        is Refinement.Refined -> completePeerSelection(evidence, admitted.value)
        is Refinement.Rejected -> rejectedPeerBudget(HostedQueryFailure.BUDGET_EXCEEDED)
    }
}

private fun peerReturnedGrant(actual: RelationBudget, offered: RelationBudget): Refinement<Unit, HostedQueryFailure> {
    if (
        actual.resources.workUnitLimit.value > offered.resources.workUnitLimit.value ||
            actual.resources.resultLimit.value > offered.resources.resultLimit.value ||
            actual.resources.elapsedTimeLimit.value > offered.resources.elapsedTimeLimit.value
    )
        return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
    return if (actual.returnedBytes.value > offered.returnedBytes.value)
        Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
    else Refinement.Refined(Unit)
}

private fun rejectedPeerBudget(failure: HostedQueryFailure) =
    HostedPeerSiteCompletion.HostedRejected(HostedResponse.ReadRejected(failure, HostedQueryStage.RESULT_DETACHED))

private fun completePeerSelection(
    evidence: HostedPeerSiteEvaluation.Selected,
    elapsed: QueryImpactPeerElapsedNanos,
): HostedPeerSiteCompletion {
    val receipt =
        when (
            val admitted =
                QueryImpactPeerAcquisitionReceipt.fromCompletedRead(
                    evidence.authority,
                    evidence.grant,
                    evidence.examinedWorkUnits,
                    elapsed,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return HostedPeerSiteCompletion.ProofRejected(admitted.failure)
        }
    return when (val admitted = QueryImpactPeerSiteAdmission.admit(evidence.selection, receipt)) {
        is Refinement.Refined -> HostedPeerSiteCompletion.Admitted(admitted.value)
        is Refinement.Rejected -> HostedPeerSiteCompletion.ProofRejected(admitted.failure)
    }
}
