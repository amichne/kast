package io.github.amichne.kast.api.continuation

fun interface ContinuationStateProjection<in State, out Projection> {
    fun project(state: State): Projection
}
