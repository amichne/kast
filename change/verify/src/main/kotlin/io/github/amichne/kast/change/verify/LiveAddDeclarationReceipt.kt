package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.LiveAppliedSourceWrite
import io.github.amichne.kast.change.apply.LiveApprovalChallenge
import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.change.apply.LiveMutationAuthority
import io.github.amichne.kast.change.apply.VerifiedLivePlanApproval
import io.github.amichne.kast.change.contract.ChangePlanId
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationDelta
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.recovery.AppliedAddDeclarationRecovery
import io.github.amichne.kast.kernel.Refinement

/** Finite failures at verified receipt construction and historical restoration. */
enum class LiveReceiptFailure {
    PLAN_MISMATCH,
    RECOVERY_MISMATCH,
    SOURCE_POSTIMAGE_MISMATCH,
    RESULTING_BASIS_MISMATCH,
    COMPILER_EVIDENCE_MISMATCH,
    VERIFICATION_EVIDENCE_INCOMPLETE,
    APPROVAL_MISMATCH,
    OBLIGATIONS_INCOMPLETE,
    MALFORMED,
    VERSION_UNSUPPORTED,
    IDENTITY_MISMATCH,
}

/** Bounded historical controller invocation. Restoring it never verifies or grants approval. */
class HistoricalLiveApproval
private constructor(
    val thread: String,
    val turn: String,
    val call: String,
    val challenge: LiveApprovalChallenge,
) {
    internal companion object {
        fun restore(
            thread: String,
            turn: String,
            call: String,
            challenge: LiveApprovalChallenge,
        ): Refinement<HistoricalLiveApproval, LiveReceiptFailure> =
            if (
                listOf(thread, turn, call).any {
                    it.isEmpty() || it.length > MAXIMUM_INVOCATION_LENGTH || it.any(Char::isISOControl)
                }
            ) {
                Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
            } else
                Refinement.Refined(
                    HistoricalLiveApproval(thread = thread, turn = turn, call = call, challenge = challenge)
                )
    }
}

/** Canonical bounded content evidence identifier; contains neither source bytes nor an executable capability. */
@JvmInline
value class HistoricalRecoveryRecordDigest private constructor(val value: String) {
    internal companion object {
        fun parse(value: String): Refinement<HistoricalRecoveryRecordDigest, LiveReceiptFailure> =
            if (value.matches(Regex("[0-9a-f]{64}"))) Refinement.Refined(HistoricalRecoveryRecordDigest(value))
            else Refinement.Rejected(LiveReceiptFailure.MALFORMED)
    }
}

/** Applied recovery chain retained independently of the source preimage bytes stored by the recovery service. */
data class HistoricalLiveRecovery
internal constructor(
    val binding: ChangePlanId,
    val preparedDigest: HistoricalRecoveryRecordDigest,
    val appliedDigest: HistoricalRecoveryRecordDigest,
)

/** Only exact source application, durable applied recovery, and complete verification can issue success. */
class VerifiedLiveAddDeclarationReceipt private constructor(val historical: HistoricalLiveAddDeclarationReceipt) {
    val identity: ChangeReceiptIdentity
        get() = historical.identity

    companion object {
        fun admit(
            write: LiveAppliedSourceWrite,
            recovery: AppliedAddDeclarationRecovery,
            verification: CompleteLiveAddDeclarationVerification,
        ): Refinement<VerifiedLiveAddDeclarationReceipt, LiveReceiptFailure> {
            when (val checked = validateVerifiedReceiptBinding(write, recovery, verification)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return checked
            }
            val approval =
                when (val admitted = historicalApproval(write.authority.approval)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val history =
                when (val admitted = historicalRecovery(recovery)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted
                }
            val observed = verification.semanticDelta.observed
            val delta =
                when (
                    val admitted =
                        ExpectedAddDeclarationDelta.admit(
                            observed.packageName,
                            observed.declarationName,
                            observed.declarationKind,
                        )
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
                }
            return restoreVerifiedHistory(
                write = write,
                verification = verification,
                delta = delta,
                approval = approval,
                recovery = history,
            )
        }

        private fun restoreVerifiedHistory(
            write: LiveAppliedSourceWrite,
            verification: CompleteLiveAddDeclarationVerification,
            delta: ExpectedAddDeclarationDelta,
            approval: HistoricalLiveApproval,
            recovery: HistoricalLiveRecovery,
        ): Refinement<VerifiedLiveAddDeclarationReceipt, LiveReceiptFailure> {
            val plan = write.authority.plan
            val result =
                HistoricalLiveSemanticResult(
                    after = verification.resulting,
                    postimage = write.content,
                    anchor = verification.anchor.evidence,
                    observedDelta = delta,
                    evidence = verification.historicalEvidence,
                )
            return when (
                val restored =
                    HistoricalLiveAddDeclarationReceipt.restore(
                        plan = plan,
                        result = result,
                        approval = approval,
                        recovery = recovery,
                        obligations =
                            HistoricalLiveReceiptObligations(
                                plan.requiredVerification.semanticObligations,
                                plan.requiredVerification.liveObligations,
                            ),
                    )
            ) {
                is Refinement.Refined -> Refinement.Refined(VerifiedLiveAddDeclarationReceipt(restored.value))
                is Refinement.Rejected -> restored
            }
        }
    }
}

private fun validateVerifiedReceiptBinding(
    write: LiveAppliedSourceWrite,
    recovery: AppliedAddDeclarationRecovery,
    verification: CompleteLiveAddDeclarationVerification,
): Refinement<Unit, LiveReceiptFailure> {
    val authority = write.authority
    val plan = authority.plan
    if (
        verification.plan.planId != plan.planId ||
            LiveAddDeclarationPlanCodec.encode(verification.plan) != LiveAddDeclarationPlanCodec.encode(plan)
    ) {
        return Refinement.Rejected(LiveReceiptFailure.PLAN_MISMATCH)
    }
    if (write.content != verification.postimage || write.content != authority.expectedPostimage) {
        return Refinement.Rejected(LiveReceiptFailure.SOURCE_POSTIMAGE_MISMATCH)
    }
    when (val checked = validateRecoveryBinding(authority, recovery)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return checked
    }
    val approved = authority.approval
    if (approved.operation != LiveChangeEffect.CHANGE_APPLY || approved.planId != plan.planId) {
        return Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
    }
    if (
        approved.owner != plan.basis.observation.reference.host ||
            approved.root != plan.basis.observation.reference.workspaceRoot
    ) {
        return Refinement.Rejected(LiveReceiptFailure.APPROVAL_MISMATCH)
    }
    return Refinement.Refined(Unit)
}

private fun validateRecoveryBinding(
    authority: LiveMutationAuthority,
    recovery: AppliedAddDeclarationRecovery,
): Refinement<Unit, LiveReceiptFailure> {
    val plan = authority.plan
    val prepared = authority.recovery
    val record = recovery.record
    val rejected = Refinement.Rejected(LiveReceiptFailure.RECOVERY_MISMATCH)
    if (prepared.record.digest != recovery.prepared.record.digest || record.priorDigest != prepared.record.digest)
        return rejected
    if (
        record.binding.value != plan.planId.value ||
            recovery.prepared.input.planId != plan.planId ||
            prepared.input.binding != record.binding
    )
        return rejected
    if (
        prepared.input.source.value != plan.target.file.path.value ||
            record.appliedWrites.sources.map { it.value } != listOf(plan.target.file.path.value)
    )
        return rejected
    val write = record.preparation.plannedWrites.singleOrNull() ?: return rejected
    if (write.source.value != plan.target.file.path.value || write.preimage.digest.value != plan.content.value)
        return rejected
    return Refinement.Refined(Unit)
}

private fun historicalApproval(
    approved: VerifiedLivePlanApproval
): Refinement<HistoricalLiveApproval, LiveReceiptFailure> =
    HistoricalLiveApproval.restore(
        thread = approved.invocation.thread,
        turn = approved.invocation.turn,
        call = approved.invocation.call,
        challenge = approved.challenge,
    )

private fun historicalRecovery(
    recovery: AppliedAddDeclarationRecovery
): Refinement<HistoricalLiveRecovery, LiveReceiptFailure> {
    val prepared =
        when (val admitted = HistoricalRecoveryRecordDigest.parse(recovery.prepared.record.digest.value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val applied =
        when (val admitted = HistoricalRecoveryRecordDigest.parse(recovery.record.digest.value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val binding =
        when (val admitted = ChangePlanId.parse(recovery.record.binding.value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.RECOVERY_MISMATCH)
        }
    return Refinement.Refined(HistoricalLiveRecovery(binding, prepared, applied))
}

private const val MAXIMUM_INVOCATION_LENGTH = 4096
