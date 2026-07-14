package io.github.amichne.kast.api.continuation

sealed interface ContinuationTransition<out Projection : ContinuationProjection, out Query> {
    data class Complete<Projection : ContinuationProjection>(
        val output: Projection,
    ) : ContinuationTransition<Projection, Nothing>

    data class Reissue<Projection : ContinuationProjection, Query>(
        val output: Projection,
        val nextQuery: Query,
    ) : ContinuationTransition<Projection, Query>
}
