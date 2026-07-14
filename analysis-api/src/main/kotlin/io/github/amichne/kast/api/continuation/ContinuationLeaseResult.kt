package io.github.amichne.kast.api.continuation

sealed interface ContinuationLeaseResult<out Projection : ContinuationProjection> {
    data class Granted<Projection : ContinuationProjection>(
        val output: Projection,
    ) : ContinuationLeaseResult<Projection>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationLeaseResult<Nothing>
}
