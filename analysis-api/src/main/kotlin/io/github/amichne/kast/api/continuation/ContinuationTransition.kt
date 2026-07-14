package io.github.amichne.kast.api.continuation

sealed interface ContinuationTransition<out Output, out Query> {
    data class Complete<Output>(val output: Output) : ContinuationTransition<Output, Nothing>

    data class Reissue<Output, Query>(
        val output: Output,
        val nextQuery: Query,
    ) : ContinuationTransition<Output, Query>
}
