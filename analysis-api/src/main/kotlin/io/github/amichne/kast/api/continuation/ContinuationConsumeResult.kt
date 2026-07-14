package io.github.amichne.kast.api.continuation

sealed interface ContinuationConsumeResult<out Token, out Projection : ContinuationProjection> {
    data class Completed<Projection : ContinuationProjection>(
        val output: Projection,
    ) : ContinuationConsumeResult<Nothing, Projection>

    data class Reissued<Token, Projection : ContinuationProjection>(
        val output: Projection,
        val token: Token,
    ) : ContinuationConsumeResult<Token, Projection>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationConsumeResult<Nothing, Nothing>
}
