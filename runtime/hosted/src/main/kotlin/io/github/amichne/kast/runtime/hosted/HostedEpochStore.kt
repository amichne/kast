package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure

/** One current partition. The original semantic owner, never a numeric revision, authorizes replacement. */
internal class HostedEpochStore<Value>(private val retireValue: (Value) -> Unit) {
    private sealed interface State<out Value> {
        data object Empty : State<Nothing>

        class Current<Value>(val authority: LiveSemanticReadAuthority, val value: Value) : State<Value>

        data object Retired : State<Nothing>
    }

    private var state: State<Value> = State.Empty

    fun admit(authority: LiveSemanticReadAuthority, create: () -> Value): Refinement<Value, LiveSemanticReadFailure> =
        when (val owned = authority.withCurrentOwner { select(authority, create) }) {
            is Refinement.Refined -> owned.value
            is Refinement.Rejected -> owned
        }

    // Lock order: semantic owner, partition, child store. No native access or semantic computation here.
    @Synchronized
    private fun select(
        authority: LiveSemanticReadAuthority,
        create: () -> Value,
    ): Refinement<Value, LiveSemanticReadFailure> {
        when (val current = state) {
            State.Retired -> return Refinement.Rejected(LiveSemanticReadFailure.RETIRED)
            State.Empty -> Unit
            is State.Current -> {
                when (val sameOwner = authority.requireSameOwner(current.authority)) {
                    is Refinement.Rejected -> return sameOwner
                    is Refinement.Refined -> Unit
                }
                if (authority === current.authority) return Refinement.Refined(current.value)
                retireValue(current.value)
            }
        }
        val value = create()
        state = State.Current(authority, value)
        return Refinement.Refined(value)
    }

    @Synchronized
    fun retire() {
        when (val current = state) {
            is State.Current -> retireValue(current.value)
            State.Empty,
            State.Retired -> Unit
        }
        state = State.Retired
    }
}
