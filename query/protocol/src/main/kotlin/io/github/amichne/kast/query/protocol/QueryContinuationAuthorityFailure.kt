package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal enum class QueryContinuationAuthorityFailure(val acquisition: QueryProducerAcquisition) {
    MISMATCH(QueryProducerAcquisition.Mismatch),
    STALE_BASIS(QueryProducerAcquisition.Rejected(QueryContinuationFailure.STALE_BASIS)),
}

/** A detached basis mismatch is proven only after the workspace association has matched. */
internal fun SemanticReadAuthority.refineContinuationAuthority(
    requested: SemanticReadAuthority
): Refinement<SemanticReadAuthority, QueryContinuationAuthorityFailure> =
    when {
        workspaceRoot != requested.workspaceRoot -> Refinement.Rejected(QueryContinuationAuthorityFailure.MISMATCH)
        identity != requested.identity -> Refinement.Rejected(QueryContinuationAuthorityFailure.STALE_BASIS)
        this != requested -> Refinement.Rejected(QueryContinuationAuthorityFailure.MISMATCH)
        else -> Refinement.Refined(requested)
    }
