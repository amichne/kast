package io.github.amichne.kast.api.continuation

fun interface ContinuationStateDisposer<in State> {
    fun dispose(state: State)
}
