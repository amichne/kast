package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Shape admission preserves receipts; it never upgrades decoded evidence into live peer authority. */
fun ImpactPeerSiteAdmissionDocument.validatePeerAdmission(
    sourceBasis: ImpactSemanticBasisDocument,
    target: ImpactValueSiteReferenceDocument,
): Refinement<Unit, ImpactPeerProofFailureDocument> {
    val receipt = acquisition
    val selected = selection
    return when {
        !receipt.budget.permitsWork(receipt.examinedWorkUnits.value) ||
            !selected.budget.permitsWork(selected.examinedWorkUnits.value) ->
            Refinement.Rejected(ImpactPeerProofFailureDocument.WORK_RECEIPT_EXCEEDS_GRANT)
        !receipt.budget.permitsElapsedMillis(receipt.elapsedNanos.elapsedMillis()) ->
            Refinement.Rejected(ImpactPeerProofFailureDocument.TIME_RECEIPT_EXCEEDS_GRANT)
        selected.examinedWorkUnits.value > receipt.examinedWorkUnits.value ->
            Refinement.Rejected(ImpactPeerProofFailureDocument.SITE_WORK_EXCEEDS_ACQUISITION_WORK)
        !selected.budget.isWithin(receipt.budget) ->
            Refinement.Rejected(ImpactPeerProofFailureDocument.SITE_GRANT_EXCEEDS_ACQUISITION_GRANT)
        !target.isOnBasis(receipt.completedBasis) ->
            Refinement.Rejected(ImpactPeerProofFailureDocument.TARGET_BASIS_MISMATCH)
        sourceBasis.root == receipt.completedBasis.root ->
            Refinement.Rejected(ImpactPeerProofFailureDocument.SAME_SOURCE_ROOT)
        else -> Refinement.Refined(Unit)
    }
}

internal fun ImpactFlowBudgetDocument.isWithin(parent: ImpactFlowBudgetDocument): Boolean =
    maxResults.value <= parent.maxResults.value &&
        maxReturnedBytes.value <= parent.maxReturnedBytes.value &&
        when (this) {
            is ImpactFlowBudgetDocument.Bounded ->
                parent.permitsWork(maxWorkUnits.value) && parent.permitsElapsedMillis(maxElapsedMillis.value)
            is ImpactFlowBudgetDocument.RunnerControlled -> parent is ImpactFlowBudgetDocument.RunnerControlled
        }

internal fun ImpactValueSiteReferenceDocument.isOnBasis(basis: ImpactSemanticBasisDocument): Boolean =
    enclosing.basis == basis &&
        admitsExecutionOwner() &&
        executionOwner.isOnBasis(basis) &&
        when (val position = role) {
            is ImpactValueRoleDocument.LoopResult -> position.result.protocol.admits()
            is ImpactValueRoleDocument.Argument -> position.invocation.isOnBasis(basis)
            is ImpactValueRoleDocument.Receiver -> position.invocation.isOnBasis(basis)
            is ImpactValueRoleDocument.Formal -> true
            ImpactValueRoleDocument.ExpressionResult,
            ImpactValueRoleDocument.LocalBinding,
            ImpactValueRoleDocument.LocalRead,
            ImpactValueRoleDocument.Return,
            ImpactValueRoleDocument.PropertyAssignment -> true
        }

private fun QueryDiscoveryCountDocument.elapsedMillis(): Long =
    value / PEER_NANOS_PER_MILLISECOND + if (value % PEER_NANOS_PER_MILLISECOND == 0L) 0L else 1L

private const val PEER_NANOS_PER_MILLISECOND = 1_000_000L
