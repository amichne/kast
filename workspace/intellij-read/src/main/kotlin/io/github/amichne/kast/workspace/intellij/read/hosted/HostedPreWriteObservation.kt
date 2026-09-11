package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference

/** One-use freshness probe. It cannot read source, approve a plan, or perform a write. */
class HostedPreWriteObservation
private constructor(
    val reference: LiveSemanticReadReference,
    private val observe: () -> Refinement<Unit, HostedQueryFailure>,
) : AutoCloseable {
    private enum class State {
        AVAILABLE,
        CONSUMED,
        CLOSED,
    }

    private var state: State = State.AVAILABLE

    /** The original owner re-observes the exact signal source inside the actual EDT write action. */
    @Synchronized
    fun consumeAtWriteBoundary(): Refinement<Unit, HostedQueryFailure> {
        if (state != State.AVAILABLE) return Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
        state = State.CONSUMED
        return observe()
    }

    @Synchronized
    override fun close() {
        state = State.CLOSED
    }

    internal companion object {
        fun capture(reference: LiveSemanticReadReference, observe: () -> Refinement<Unit, HostedQueryFailure>) =
            HostedPreWriteObservation(reference, observe)
    }
}
