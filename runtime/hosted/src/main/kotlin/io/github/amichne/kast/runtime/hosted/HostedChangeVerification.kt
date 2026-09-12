package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.LiveAppliedDurabilityState
import io.github.amichne.kast.change.apply.LiveAppliedSourceWrite
import io.github.amichne.kast.change.intellij.HostedLiveAddDeclarationVerifier
import io.github.amichne.kast.change.protocol.liveChangeEvidence
import io.github.amichne.kast.change.protocol.protocolPreview
import io.github.amichne.kast.change.verify.LiveAddDeclarationVerificationPorts
import io.github.amichne.kast.change.verify.LiveChangeReceiptIssuance
import io.github.amichne.kast.change.verify.VerifiedLiveAddDeclarationReceipt
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

/** Verification is part of apply completion and reacquires its own live read after the source effect. */
internal suspend fun completeHostedApplication(
    project: Project,
    query: HostedQueryService,
    resources: HostedChangeResources,
    write: LiveAppliedSourceWrite,
    durability: LiveAppliedDurabilityState,
): HostedApplyOutcome {
    val plan = write.authority.plan
    val applied =
        when (durability) {
            is LiveAppliedDurabilityState.Recorded -> durability.recovery
            LiveAppliedDurabilityState.Pending,
            LiveAppliedDurabilityState.Rejected ->
                return recoveryRequired(plan, ChangeApplyRecoveryReason.DURABILITY_REJECTED)
        }
    val verification =
        when (
            val read =
                query.read(query.endpoint, plan.basis.observation.reference.workspaceRoot) { context ->
                    HostedLiveAddDeclarationVerifier.verify(
                        project = project,
                        context = context,
                        plan = plan,
                        expectedPostimage = write.content,
                        ports = verificationPorts(project, context),
                    )
                }
        ) {
            is HostedSemanticReadResult.Completed ->
                when (val verified = read.value) {
                    is Refinement.Refined -> verified.value
                    is Refinement.Rejected -> return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_FAILED)
                }
            is HostedSemanticReadResult.Rejected ->
                return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_UNAVAILABLE)
        }
    val receipt =
        when (val admitted = VerifiedLiveAddDeclarationReceipt.admit(write, applied, verification)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_FAILED)
        }
    return when (val issued = resources.receipts.issueReceipt(receipt)) {
        is LiveChangeReceiptIssuance.Issued ->
            OperationOutcome.Complete(
                liveChangeEvidence(
                    CanonicalOperation.CHANGE_APPLY,
                    issued.receipt.after.reference,
                    ChangeApplyResult.Verified(hostedProtocolText(issued.identity.value), plan.protocolPreview()),
                )
            )
        is LiveChangeReceiptIssuance.Rejected ->
            unverified(plan, ChangeApplyUnverifiedReason.RECEIPT_PERSISTENCE_FAILED)
    }
}

private fun verificationPorts(
    project: Project,
    context: HostedSemanticReadContext,
): LiveAddDeclarationVerificationPorts {
    val services = HostedSemanticServices(project, context)
    return LiveAddDeclarationVerificationPorts(
        services.relations,
        traversalOperations(services.relations),
        services.diagnostics,
    )
}
