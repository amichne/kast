package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.LiveApprovalChallenge
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.ChangePlanId
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationObligation
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

internal fun decodeReceiptBody(
    body: LiveReceiptBody
): Refinement<HistoricalLiveAddDeclarationReceipt, LiveReceiptFailure> {
    val plan =
        when (val decoded = LiveAddDeclarationPlanCodec.decode(body.plan.toString())) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.PLAN_MISMATCH)
        }
    val result =
        when (val decoded = decodeReceiptResult(plan, body)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val approval =
        when (val decoded = decodeReceiptApproval(body.approval)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val recovery =
        when (val decoded = decodeReceiptRecovery(body.recovery)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val obligations =
        when (val decoded = decodeReceiptObligations(body)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    return HistoricalLiveAddDeclarationReceipt.restore(
        plan = plan,
        result = result,
        approval = approval,
        recovery = recovery,
        obligations = obligations,
    )
}

private fun decodeReceiptResult(
    plan: LiveAddDeclarationChangePlan,
    body: LiveReceiptBody,
): Refinement<HistoricalLiveSemanticResult, LiveReceiptFailure> {
    val after =
        when (val decoded = decodeReceiptAfter(plan, body)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val postimage =
        when (val decoded = WorkspaceSourceContentHash.parse(body.postimage)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    val anchor =
        when (val decoded = decodeReceiptAnchor(plan, body.anchor)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val kind =
        AddDeclarationKind.entries.singleOrNull { it.name == body.delta.kind }
            ?: return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    val delta =
        when (val decoded = ExpectedAddDeclarationDelta.admit(body.delta.packageName, body.delta.name, kind)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
        }
    return Refinement.Refined(
        HistoricalLiveSemanticResult(
            after = after,
            postimage = postimage,
            anchor = anchor,
            observedDelta = delta,
            evidence = body.evidence,
        )
    )
}

private fun decodeReceiptApproval(body: LiveReceiptApproval): Refinement<HistoricalLiveApproval, LiveReceiptFailure> {
    val challenge =
        when (val decoded = LiveApprovalChallenge.parse(body.challenge)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
        }
    return HistoricalLiveApproval.restore(
        thread = body.thread,
        turn = body.turn,
        call = body.call,
        challenge = challenge,
    )
}

private fun decodeReceiptRecovery(body: LiveReceiptRecovery): Refinement<HistoricalLiveRecovery, LiveReceiptFailure> {
    val binding =
        when (val decoded = ChangePlanId.parse(body.binding)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.RECOVERY_MISMATCH)
        }
    val prepared =
        when (val decoded = HistoricalRecoveryRecordDigest.parse(body.prepared)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    val applied =
        when (val decoded = HistoricalRecoveryRecordDigest.parse(body.applied)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return decoded
        }
    return Refinement.Refined(HistoricalLiveRecovery(binding, prepared, applied))
}

private fun decodeReceiptObligations(
    body: LiveReceiptBody
): Refinement<HistoricalLiveReceiptObligations, LiveReceiptFailure> {
    val semantic =
        body.semanticObligations.map { name ->
            AddDeclarationObligation.entries.singleOrNull { it.name == name }
                ?: return Refinement.Rejected(LiveReceiptFailure.OBLIGATIONS_INCOMPLETE)
        }
    val live =
        body.liveObligations.map { name ->
            LiveAddDeclarationObligation.entries.singleOrNull { it.name == name }
                ?: return Refinement.Rejected(LiveReceiptFailure.OBLIGATIONS_INCOMPLETE)
        }
    return Refinement.Refined(HistoricalLiveReceiptObligations(semantic, live))
}
