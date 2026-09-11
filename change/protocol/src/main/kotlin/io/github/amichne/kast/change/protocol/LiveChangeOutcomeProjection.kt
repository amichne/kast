package io.github.amichne.kast.change.protocol

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference

/** Historical operation evidence; no published generation or current read authority is constructed. */
fun <Payload> liveChangeEvidence(
    operation: CanonicalOperation,
    reference: LiveSemanticReadReference,
    payload: Payload,
): EvidenceEnvelope<Payload> {
    val evidence =
        when (
            val admitted =
                LiveReadEvidence.create(
                    workspaceRoot = reference.workspaceRoot.value,
                    host = reference.host.value,
                    epoch = reference.epoch.value,
                    contentView = LiveReadContentView.SAVED_PSI_COMMITTED,
                    version = reference.version,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Admitted live change reference cannot be projected")
        }
    return EvidenceEnvelope(operation.id, EvidenceBasis.Live(evidence), payload)
}
