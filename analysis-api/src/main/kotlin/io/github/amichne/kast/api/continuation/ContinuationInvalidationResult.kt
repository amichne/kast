package io.github.amichne.kast.api.continuation

sealed interface ContinuationInvalidationResult {
    data object Invalidated : ContinuationInvalidationResult

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationInvalidationResult
}
