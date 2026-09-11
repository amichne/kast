package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.DurableAddDeclarationPlanningEvidence
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationObligation
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

/** Historical compiler/source data supplied to restoration; carries no live authority. */
internal data class HistoricalLiveSemanticResult(
    val after: LiveChangeBasis,
    val postimage: WorkspaceSourceContentHash,
    val anchor: CompilerGroundedSymbolEvidence,
    val observedDelta: ExpectedAddDeclarationDelta,
    val evidence: DurableAddDeclarationPlanningEvidence,
)

internal class HistoricalLiveReceiptObligations(
    semantic: List<AddDeclarationObligation>,
    live: List<LiveAddDeclarationObligation>,
) {
    val semantic: List<AddDeclarationObligation> = semantic.toList()
    val live: List<LiveAddDeclarationObligation> = live.toList()
}

/** Historical success evidence only; restoration grants no current read, approval, or mutation capability. */
class HistoricalLiveAddDeclarationReceipt
private constructor(
    val plan: LiveAddDeclarationChangePlan,
    private val result: HistoricalLiveSemanticResult,
    val approval: HistoricalLiveApproval,
    val recovery: HistoricalLiveRecovery,
) {
    val before: LiveChangeBasis
        get() = plan.basis.observation

    val after: LiveChangeBasis
        get() = result.after

    val postimage: WorkspaceSourceContentHash
        get() = result.postimage

    val anchor: CompilerGroundedSymbolEvidence
        get() = result.anchor

    val observedDelta: ExpectedAddDeclarationDelta
        get() = result.observedDelta

    val evidence: DurableAddDeclarationPlanningEvidence
        get() = result.evidence

    val source
        get() = plan.target.file

    val verificationScope
        get() = plan.verificationScope

    val semanticObligations: List<AddDeclarationObligation>
        get() = plan.requiredVerification.semanticObligations

    val liveObligations: List<LiveAddDeclarationObligation>
        get() = plan.requiredVerification.liveObligations

    val identity: ChangeReceiptIdentity
        get() = LiveAddDeclarationReceiptCodec.identity(this)

    internal companion object {
        fun restore(
            plan: LiveAddDeclarationChangePlan,
            result: HistoricalLiveSemanticResult,
            approval: HistoricalLiveApproval,
            recovery: HistoricalLiveRecovery,
            obligations: HistoricalLiveReceiptObligations,
        ): Refinement<HistoricalLiveAddDeclarationReceipt, LiveReceiptFailure> {
            when (val checked = validateHistoricalResult(plan, result)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return checked
            }
            val historical =
                when (val checked = validateHistoricalEvidence(plan, result.evidence)) {
                    is Refinement.Refined -> checked.value
                    is Refinement.Rejected -> return checked
                }
            if (recovery.binding != plan.planId || recovery.preparedDigest == recovery.appliedDigest) {
                return Refinement.Rejected(LiveReceiptFailure.RECOVERY_MISMATCH)
            }
            if (
                obligations.semantic != plan.requiredVerification.semanticObligations ||
                    obligations.live != plan.requiredVerification.liveObligations
            ) {
                return Refinement.Rejected(LiveReceiptFailure.OBLIGATIONS_INCOMPLETE)
            }
            return Refinement.Refined(
                HistoricalLiveAddDeclarationReceipt(
                    plan = plan,
                    result = result.copy(evidence = historical),
                    approval = approval,
                    recovery = recovery,
                )
            )
        }
    }
}

private fun validateHistoricalResult(
    plan: LiveAddDeclarationChangePlan,
    result: HistoricalLiveSemanticResult,
): Refinement<Unit, LiveReceiptFailure> {
    when (val checked = validateHistoricalBasis(plan.basis.observation, result.after)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return checked
    }
    if (result.postimage == plan.content) return Refinement.Rejected(LiveReceiptFailure.SOURCE_POSTIMAGE_MISMATCH)
    if (CompilerReobservedMutationAnchor.admit(plan.target.evidence, result.anchor) is Refinement.Rejected) {
        return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    }
    val expected = plan.expectedSemanticDelta
    if (
        result.observedDelta.packageName != expected.packageName ||
            result.observedDelta.declarationName != expected.declarationName ||
            result.observedDelta.declarationKind != expected.declarationKind
    ) {
        return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    }
    return Refinement.Refined(Unit)
}

private fun validateHistoricalBasis(
    before: LiveChangeBasis,
    after: LiveChangeBasis,
): Refinement<Unit, LiveReceiptFailure> {
    val rejected = Refinement.Rejected(LiveReceiptFailure.RESULTING_BASIS_MISMATCH)
    if (
        after.reference.workspaceRoot != before.reference.workspaceRoot || after.reference.host != before.reference.host
    )
        return rejected
    if (
        after.reference.version != LiveSemanticReadReference.VERSION ||
            after.reference.epoch.value < before.reference.epoch.value ||
            after.reference.contentView != IdeReadContentView.SAVED_PSI_COMMITTED
    )
        return rejected
    if (after.model.workspaceRoot != before.model.workspaceRoot || after.model.sourceRoots != before.model.sourceRoots)
        return rejected
    return Refinement.Refined(Unit)
}

private fun validateHistoricalEvidence(
    plan: LiveAddDeclarationChangePlan,
    evidence: DurableAddDeclarationPlanningEvidence,
): Refinement<DurableAddDeclarationPlanningEvidence, LiveReceiptFailure> {
    val historical =
        when (val restored = DurableAddDeclarationPlanningEvidence.restoreHistorical(evidence)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.VERIFICATION_EVIDENCE_INCOMPLETE)
        }
    val scope = plan.verificationScope
    if (
        historical.relations.size != scope.relations.size ||
            historical.traversals.size != scope.traversals.size ||
            historical.diagnostics.size != scope.diagnostics.size
    ) {
        return Refinement.Rejected(LiveReceiptFailure.VERIFICATION_EVIDENCE_INCOMPLETE)
    }
    if (
        historical.relations.groupingBy { it.meaning to it.stableDigest }.eachCount() !=
            plan.evidence.relations.groupingBy { it.meaning to it.stableDigest }.eachCount()
    ) {
        return Refinement.Rejected(LiveReceiptFailure.VERIFICATION_EVIDENCE_INCOMPLETE)
    }
    return Refinement.Refined(historical)
}
