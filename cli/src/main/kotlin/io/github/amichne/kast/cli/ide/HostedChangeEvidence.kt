package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRecoveryDocumentState

/** Unresolved recovery reports retained plan evidence; it does not prove the current source state. */
internal fun admitHostedChangeOutcome(
    outcome: OperationOutcome<*, *, *>,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
    operation: ExistingIdeOperation,
): Refinement<Unit, ExistingIdeFailure> =
    when (outcome) {
        is OperationOutcome.Complete ->
            admitHostedChangeEvidence(
                evidence = outcome.evidence,
                root = root,
                descriptor = descriptor,
                operation = operation,
            )
        is OperationOutcome.Qualified ->
            if (historicalRecoveryRequirement(outcome, operation)) admitHistoricalEvidence(outcome.evidence, root)
            else
                admitHostedChangeEvidence(
                    evidence = outcome.evidence,
                    root = root,
                    descriptor = descriptor,
                    operation = operation,
                )
        is OperationOutcome.Rejected -> Refinement.Refined(Unit)
    }

private fun historicalRecoveryRequirement(
    outcome: OperationOutcome.Qualified<*, *>,
    operation: ExistingIdeOperation,
): Boolean =
    when (operation) {
        is ExistingIdeOperation.ApprovedMutation ->
            when (operation.kind) {
                HostedMutationOperation.CHANGE_APPLY -> false
                HostedMutationOperation.CHANGE_RECOVER -> {
                    val result = outcome.evidence.payload
                    outcome.qualification == ChangeRecoverQualification.MANUAL_RECOVERY_REQUIRED &&
                        result is ChangeRecoverResult &&
                        result.state == ChangeRecoveryDocumentState.RECOVERY_REQUIRED
                }
            }
        else -> false
    }

private fun admitHistoricalEvidence(
    evidence: EvidenceEnvelope<*>,
    root: CanonicalRoot,
): Refinement<Unit, ExistingIdeFailure> =
    when (val basis = evidence.basis) {
        is EvidenceBasis.Live ->
            if (basis.evidence.workspaceRoot == root.path.toString()) Refinement.Refined(Unit)
            else Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        is EvidenceBasis.Published -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    }

/**
 * Verified receipts and interrupted durable attempts are historical evidence. The admitted socket identifies their
 * current delivery owner while stored evidence retains the original owner. Other effects require the current owner;
 * root correlation is never relaxed.
 */
internal fun admitHostedChangeEvidence(
    evidence: EvidenceEnvelope<*>,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
    operation: ExistingIdeOperation,
): Refinement<Unit, ExistingIdeFailure> =
    if (
        operation is ExistingIdeOperation.ApprovedMutation &&
            operation.kind == HostedMutationOperation.CHANGE_APPLY &&
            historicalApplyState(evidence.payload)
    ) {
        admitHistoricalEvidence(evidence, root)
    } else admitLiveEvidence(evidence.basis, root, descriptor)

private fun historicalApplyState(payload: Any?): Boolean =
    when (payload) {
        is ChangeApplyResult.Verified -> true
        is ChangeApplyResult.RecoveryRequired -> payload.reason == ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED
        else -> false
    }
