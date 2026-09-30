package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement

/** One attempt's detached store publication, committed only after freshness, deadline and permit completion. */
interface HostedReadPublicationEffect {
    fun commit(): Refinement<Unit, HostedQueryFailure>

    fun discard()
}

internal class HostedReadPublicationOwner {
    private sealed interface State {
        data object Empty : State

        data class Prepared(val effect: HostedReadPublicationEffect) : State

        data object Ended : State
    }

    private var state: State = State.Empty

    @Synchronized
    fun prepare(effect: HostedReadPublicationEffect): Refinement<Unit, HostedQueryFailure> =
        when (state) {
            State.Empty -> {
                state = State.Prepared(effect)
                Refinement.Refined(Unit)
            }
            is State.Prepared,
            State.Ended -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        }

    @Synchronized
    fun commit(): Refinement<Unit, HostedQueryFailure> {
        val current = state
        state = State.Ended
        return when (current) {
            State.Empty -> Refinement.Refined(Unit)
            is State.Prepared ->
                current.effect.commit().also {
                    if (it is Refinement.Rejected) current.effect.discard()
                }
            State.Ended -> Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        }
    }

    @Synchronized
    fun discard() {
        val current = state
        state = State.Ended
        if (current is State.Prepared) current.effect.discard()
    }

    @Synchronized
    fun restart() {
        discard()
        state = State.Empty
    }
}
