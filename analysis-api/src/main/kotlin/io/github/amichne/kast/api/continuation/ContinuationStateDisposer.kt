package io.github.amichne.kast.api.continuation

fun interface ContinuationStateDisposer<in State : ContinuationOwnedState> {
    fun dispose(state: State)
}
