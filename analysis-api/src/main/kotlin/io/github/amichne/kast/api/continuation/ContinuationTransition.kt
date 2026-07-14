package io.github.amichne.kast.api.continuation

sealed interface ContinuationTransition<out Projection, out Query> {
    data class Complete<Projection>(val output: Projection) : ContinuationTransition<Projection, Nothing>

    data class Reissue<Projection, Query>(
        val output: Projection,
        val nextQuery: Query,
    ) : ContinuationTransition<Projection, Query>
}
