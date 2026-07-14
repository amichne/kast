package io.github.amichne.kast.api.continuation

fun interface ContinuationStateTransition<
    in State : ContinuationOwnedState,
    out Projection : ContinuationProjection,
    out Query,
> {
    fun transition(state: State): ContinuationTransition<Projection, Query>
}
