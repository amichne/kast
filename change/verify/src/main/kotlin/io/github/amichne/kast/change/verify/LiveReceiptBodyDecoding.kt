package io.github.amichne.kast.change.verify

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
    body: LiveReceiptFacts,
    execution: HistoricalLiveExecution,
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
        execution = execution,
        recovery = recovery,
        obligations = obligations,
    )
}

private fun decodeReceiptResult(
    plan: LiveAddDeclarationChangePlan,
    body: LiveReceiptFacts,
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

internal fun decodeReceiptApproval(body: LiveReceiptApproval): Refinement<HistoricalLiveApproval, LiveReceiptFailure> {
    val challenge =
        when (val decoded = HistoricalApprovalChallenge.parse(body.challenge)) {
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

internal fun decodeReceiptRecovery(body: LiveReceiptRecovery): Refinement<HistoricalLiveRecovery, LiveReceiptFailure> {
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
    body: LiveReceiptFacts
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

internal fun decodeReceiptExecution(
    document: LocalEndpointExecutionDocument,
    plan: io.github.amichne.kast.change.contract.LiveChangePlan,
): Refinement<HistoricalLiveExecution.LocalEndpointOperation, LiveReceiptFailure> {
    val identity =
        when (val parsed = ChangePlanId.parse(document.planId)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    val path =
        try {
            java.nio.file.Path.of(document.root)
        } catch (_: java.nio.file.InvalidPathException) {
            return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    val root =
        when (val parsed = io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot.fromCanonicalPath(path)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    val host =
        try {
            java.util.UUID.fromString(document.host)
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    if (host.toString() != document.host) return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
    return HistoricalLiveExecution.LocalEndpointOperation.restore(
        plan,
        identity,
        root,
        io.github.amichne.kast.workspace.contract.IdeReadHostLifetime.fromBoundary(host),
        document.operation,
    )
}

internal fun HistoricalLiveExecution.LocalEndpointOperation.document() =
    LocalEndpointExecutionDocument(
        LocalEndpointExecutionType.LOCAL_ENDPOINT_OPERATION,
        operation,
        root.value,
        host.value.toString(),
        planId.value,
    )
