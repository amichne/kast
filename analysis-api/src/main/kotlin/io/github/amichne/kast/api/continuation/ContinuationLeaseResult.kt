package io.github.amichne.kast.api.continuation

sealed interface ContinuationLeaseResult<out Output> {
    data class Granted<Output>(val output: Output) : ContinuationLeaseResult<Output>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationLeaseResult<Nothing>
}
