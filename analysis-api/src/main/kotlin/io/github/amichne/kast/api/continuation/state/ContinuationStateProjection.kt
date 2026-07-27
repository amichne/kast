package io.github.amichne.kast.api.continuation

fun interface ContinuationStateProjection<
    in State : ContinuationOwnedState,
    out Projection : ContinuationProjection,
> {
    fun project(state: State): Projection
}
