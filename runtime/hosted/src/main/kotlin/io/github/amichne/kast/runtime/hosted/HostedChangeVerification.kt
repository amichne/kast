package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.amichne.kast.change.apply.LiveAppliedDurabilityState
import io.github.amichne.kast.change.apply.LiveAppliedSourceWrite
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveReplaceBodyChangePlan
import io.github.amichne.kast.change.intellij.HostedLiveAddDeclarationVerifier
import io.github.amichne.kast.change.intellij.HostedLiveReplaceBodyVerifier
import io.github.amichne.kast.change.protocol.liveChangeEvidence
import io.github.amichne.kast.change.protocol.protocolPreview
import io.github.amichne.kast.change.verify.LiveAddDeclarationVerificationPorts
import io.github.amichne.kast.change.verify.LiveChangeReceiptIssuance
import io.github.amichne.kast.change.verify.VerifiedLiveAddDeclarationReceipt
import io.github.amichne.kast.change.verify.VerifiedLiveReplaceBodyReceipt
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import io.github.amichne.kast.query.protocol.CanonicalSelectorCodec
import io.github.amichne.kast.query.protocol.CanonicalSelectorEncoding
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadContext
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult

/** Verification is part of apply completion and reacquires its own live read after the source effect. */
@Suppress("LongMethod") // Keep the post-write proof and receipt issuance in one ordered effect boundary.
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
    return when (plan) {
        is LiveAddDeclarationChangePlan ->
            completeHostedAddDeclarationApplication(
                project,
                query,
                resources,
                write,
                applied,
                plan,
            )
        is LiveReplaceBodyChangePlan ->
            completeHostedReplaceBodyApplication(
                project,
                query,
                resources,
                write,
                applied,
                plan,
            )
    }
}

@Suppress("LongMethod") // Keep the ordered mutation phases at their existing owner.
private suspend fun completeHostedAddDeclarationApplication(
    project: Project,
    query: HostedQueryService,
    resources: HostedChangeResources,
    write: LiveAppliedSourceWrite,
    applied: io.github.amichne.kast.change.recovery.AppliedAddDeclarationRecovery,
    plan: LiveAddDeclarationChangePlan,
): HostedApplyOutcome {
    val verification =
        when (
            val read =
                observeHostedChange(HostedChangeStage.VERIFICATION, plan.planId) {
                    retryPresemanticIndexing(
                        read = {
                            query.read(query.endpoint, plan.basis.observation.reference.workspaceRoot) { context ->
                                HostedLiveAddDeclarationVerifier.verify(
                                    project,
                                    context,
                                    plan,
                                    write.content,
                                    verificationPorts(project, context),
                                )
                            }
                        },
                        wait = {
                            observeHostedChange(HostedChangeStage.READINESS_WAIT, plan.planId) {
                                awaitHostedSmartMode(project)
                            }
                        },
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
    return when (
        val issued =
            observeHostedChange(HostedChangeStage.RECEIPT_PERSISTENCE, plan.planId) {
                resources.receipts.issueReceipt(receipt)
            }
    ) {
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

@Suppress("LongMethod") // Keep post-write verification and receipt issuance in one ordered effect boundary.
private suspend fun completeHostedReplaceBodyApplication(
    project: Project,
    query: HostedQueryService,
    resources: HostedChangeResources,
    write: LiveAppliedSourceWrite,
    applied: io.github.amichne.kast.change.recovery.AppliedAddDeclarationRecovery,
    plan: LiveReplaceBodyChangePlan,
): HostedApplyOutcome {
    val logger = Logger.getInstance(HostedChangeCoordinator::class.java)
    val verification =
        when (
            val read =
                observeHostedChange(HostedChangeStage.VERIFICATION, plan.planId) {
                    retryPresemanticIndexing(
                        read = {
                            query.read(query.endpoint, plan.basis.observation.reference.workspaceRoot) { context ->
                                HostedLiveReplaceBodyVerifier.verify(
                                    project,
                                    context,
                                    plan,
                                    write.content,
                                    HostedSemanticServices(
                                            project,
                                            context,
                                            io.github.amichne.kast.query.protocol.QueryReferenceTransport.Inline,
                                        )
                                        .diagnostics,
                                )
                            }
                        },
                        wait = {
                            observeHostedChange(HostedChangeStage.READINESS_WAIT, plan.planId) {
                                awaitHostedSmartMode(project)
                            }
                        },
                    )
                }
        ) {
            is HostedSemanticReadResult.Completed ->
                when (val verified = read.value) {
                    is Refinement.Refined -> verified.value
                    is Refinement.Rejected -> {
                        logger.info(
                            "kast_replace_body_completion stage=VERIFICATION outcome=REJECTED " +
                                "reason=${verified.failure}"
                        )
                        return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_FAILED)
                    }
                }
            is HostedSemanticReadResult.Rejected -> {
                logger.info("kast_replace_body_completion stage=VERIFICATION outcome=UNAVAILABLE")
                return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_UNAVAILABLE)
            }
        }
    val fresh =
        when (val encoded = CanonicalSelectorCodec.encodeExact(verification.freshReference)) {
            is CanonicalSelectorEncoding.Encoded -> encoded.token
            is CanonicalSelectorEncoding.Rejected -> {
                logger.info("kast_replace_body_completion stage=FRESH_REFERENCE outcome=REJECTED")
                return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_FAILED)
            }
        }
    logger.info("kast_replace_body_completion stage=FRESH_REFERENCE outcome=COMPLETED")
    val receipt =
        when (val admitted = VerifiedLiveReplaceBodyReceipt.admit(write, applied, verification, fresh.value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> {
                logger.info(
                    "kast_replace_body_completion stage=RECEIPT_ADMISSION outcome=REJECTED reason=${admitted.failure}"
                )
                return unverified(plan, ChangeApplyUnverifiedReason.VERIFICATION_FAILED)
            }
        }
    logger.info("kast_replace_body_completion stage=RECEIPT_ADMISSION outcome=COMPLETED")
    return when (
        val issued =
            observeHostedChange(HostedChangeStage.RECEIPT_PERSISTENCE, plan.planId) {
                resources.receipts.issueReceipt(receipt)
            }
    ) {
        is LiveChangeReceiptIssuance.Issued -> {
            logger.info("kast_replace_body_completion stage=RECEIPT_ISSUANCE outcome=COMPLETED")
            OperationOutcome.Complete(
                liveChangeEvidence(
                    CanonicalOperation.CHANGE_APPLY,
                    issued.receipt.after.reference,
                    ChangeApplyResult.VerifiedBody(
                        hostedProtocolText(issued.identity.value),
                        fresh,
                        plan.protocolPreview(),
                    ),
                )
            )
        }
        is LiveChangeReceiptIssuance.Rejected -> {
            logger.info("kast_replace_body_completion stage=RECEIPT_ISSUANCE outcome=REJECTED reason=${issued.failure}")
            unverified(plan, ChangeApplyUnverifiedReason.RECEIPT_PERSISTENCE_FAILED)
        }
    }
}

private fun verificationPorts(
    project: Project,
    context: HostedSemanticReadContext,
): LiveAddDeclarationVerificationPorts {
    val services =
        HostedSemanticServices(project, context, io.github.amichne.kast.query.protocol.QueryReferenceTransport.Inline)
    return LiveAddDeclarationVerificationPorts(
        services.relations,
        traversalOperations(services.relations),
        services.diagnostics,
    )
}
