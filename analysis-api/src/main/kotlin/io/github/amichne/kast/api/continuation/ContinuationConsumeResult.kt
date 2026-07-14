package io.github.amichne.kast.api.continuation

sealed interface ContinuationConsumeResult<out Token, out Output> {
    data class Completed<Output>(val output: Output) : ContinuationConsumeResult<Nothing, Output>

    data class Reissued<Token, Output>(
        val output: Output,
        val token: Token,
    ) : ContinuationConsumeResult<Token, Output>

    data class Rejected(
        val failure: ContinuationAccessFailure,
    ) : ContinuationConsumeResult<Nothing, Nothing>
}
