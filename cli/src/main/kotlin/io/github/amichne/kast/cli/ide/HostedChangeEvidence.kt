package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyResult

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
        when (val basis = evidence.basis) {
            is EvidenceBasis.Live ->
                if (basis.evidence.workspaceRoot == root.path.toString()) Refinement.Refined(Unit)
                else Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
            is EvidenceBasis.Published -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        }
    } else admitLiveEvidence(evidence.basis, root, descriptor)

private fun historicalApplyState(payload: Any?): Boolean =
    when (payload) {
        is ChangeApplyResult.Verified -> true
        is ChangeApplyResult.RecoveryRequired -> payload.reason == ChangeApplyRecoveryReason.ATTEMPT_INTERRUPTED
        else -> false
    }
