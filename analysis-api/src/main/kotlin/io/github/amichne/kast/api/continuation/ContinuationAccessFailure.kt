package io.github.amichne.kast.api.continuation

sealed interface ContinuationAccessFailure {
    data object StoreClosed : ContinuationAccessFailure

    data object UnknownToken : ContinuationAccessFailure

    data object ExpiredToken : ContinuationAccessFailure

    data object QueryMismatch : ContinuationAccessFailure

    data object TokenCollision : ContinuationAccessFailure
}
