package io.github.amichne.kast.api.continuation

sealed interface ContinuationLeaseResult<out Projection> {
    data class Granted<Projection>(val output: Projection) : ContinuationLeaseResult<Projection>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationLeaseResult<Nothing>
}
