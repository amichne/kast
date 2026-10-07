package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryOriginalFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection

/** A completion verdict is not an original execution failure. */
internal sealed interface QueryOriginalFailureRejection {
    data object CompletionPolicyFailure : QueryOriginalFailureRejection
}

internal fun QueryRunRejection.originalFailure():
    Refinement<QueryOriginalFailureDocument, QueryOriginalFailureRejection> =
    when (this) {
        is QueryRunRejection.ImpactExecutionRejected -> Refinement.Refined(this)
        is QueryRunRejection.ImpactSourceRejected -> Refinement.Refined(this)
        is QueryRunRejection.ImpactPresentationRejected -> Refinement.Refined(this)
        QueryRunRejection.WorkspaceNotReady -> Refinement.Refined(QueryRunRejection.WorkspaceNotReady)
        is QueryRunRejection.ReferenceRejected -> Refinement.Refined(this)
        is QueryRunRejection.StepReferenceRejected -> Refinement.Refined(this)
        is QueryRunRejection.SourceRejected -> Refinement.Refined(this)
        is QueryRunRejection.ExecutionRejected -> Refinement.Refined(this)
        is QueryRunRejection.CompletionUnsupported,
        is QueryRunRejection.CompletionUnproven ->
            Refinement.Rejected(QueryOriginalFailureRejection.CompletionPolicyFailure)
    }
