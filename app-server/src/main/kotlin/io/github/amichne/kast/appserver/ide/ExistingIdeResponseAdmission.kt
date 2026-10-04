package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding

/** Pure canonical and host admission. The existing public adapter retains its rejection contract. */
internal fun ExistingIdeReadOperation.admitResponse(
    raw: String,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
): Refinement<Unit, ExistingIdeResponseDecodeFailure> {
    val decoded =
        when (this) {
            ExistingIdeReadOperation.QUERY_RUN -> CanonicalOperationWireBindings.queryRun.decodeOutcome(raw)
            ExistingIdeReadOperation.SOURCE_READ -> CanonicalOperationWireBindings.sourceRead.decodeOutcome(raw)
            ExistingIdeReadOperation.DIAGNOSTIC_CHECK ->
                CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(raw)
        }
    return when (decoded) {
        is WireDecoding.Rejected -> Refinement.Rejected(ExistingIdeResponseDecodeFailure.Wire(decoded.failure))
        is WireDecoding.Decoded ->
            when (val outcome = decoded.value) {
                is OperationOutcome.Complete -> admitResponseBasis(outcome.evidence.basis, root, descriptor)
                is OperationOutcome.Qualified -> admitResponseBasis(outcome.evidence.basis, root, descriptor)
                is OperationOutcome.Rejected -> Refinement.Refined(Unit)
            }
    }
}

internal fun admitResponseBasis(
    basis: EvidenceBasis,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
): Refinement<Unit, ExistingIdeResponseDecodeFailure> =
    when (basis) {
        is EvidenceBasis.Published ->
            Refinement.Rejected(ExistingIdeResponseDecodeFailure.Basis(ExistingIdeResponseBasisFailure.PUBLISHED))
        is EvidenceBasis.Live ->
            when {
                basis.evidence.workspaceRoot != root.path.toString() ->
                    Refinement.Rejected(
                        ExistingIdeResponseDecodeFailure.Basis(ExistingIdeResponseBasisFailure.ROOT_MISMATCH)
                    )
                basis.evidence.host != descriptor.host ->
                    Refinement.Rejected(
                        ExistingIdeResponseDecodeFailure.Basis(ExistingIdeResponseBasisFailure.HOST_MISMATCH)
                    )
                else -> Refinement.Refined(Unit)
            }
    }
