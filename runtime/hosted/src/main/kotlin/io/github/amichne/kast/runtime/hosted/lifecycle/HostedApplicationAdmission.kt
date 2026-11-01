package io.github.amichne.kast.runtime.hosted.lifecycle

/** The application owns admission across project requests and workspace preparation. */
internal class HostedApplicationAdmission {
    private sealed interface State {
        data object Open : State

        data class Fenced(val transaction: String) : State
    }

    private var state: State = State.Open
    private val active = mutableSetOf<Lease>()

    @Synchronized
    fun enter(): Admission =
        when (state) {
            State.Open ->
                Lease(this).let { lease ->
                    active.add(lease)
                    Admission.Admitted(lease)
                }
            is State.Fenced -> Admission.Fenced
        }

    @Synchronized
    fun settle(transaction: String): Settlement {
        when (val current = state) {
            State.Open -> state = State.Fenced(transaction)
            is State.Fenced -> if (current.transaction != transaction) return Settlement.Conflict
        }
        return if (active.isEmpty()) Settlement.Idle else Settlement.Busy(active.size)
    }

    @Synchronized
    fun resume(transaction: String): Resumption =
        when (val current = state) {
            State.Open -> Resumption.Conflict
            is State.Fenced ->
                if (current.transaction == transaction && active.isEmpty()) {
                    state = State.Open
                    Resumption.Resumed
                } else Resumption.Conflict
        }

    @Synchronized fun fencedAndIdle(): Boolean = state is State.Fenced && active.isEmpty()

    @Synchronized fun settledFor(transaction: String): Boolean = state == State.Fenced(transaction) && active.isEmpty()

    @Synchronized
    private fun release(lease: Lease) {
        check(active.remove(lease)) { "Host operation lease already completed" }
    }

    class Lease private constructor(private val owner: HostedApplicationAdmission) : AutoCloseable {
        override fun close() = owner.release(this)

        companion object {
            internal operator fun invoke(owner: HostedApplicationAdmission): Lease = Lease(owner)
        }
    }

    sealed interface Admission {
        data class Admitted(val lease: Lease) : Admission

        data object Fenced : Admission
    }

    sealed interface Settlement {
        data object Idle : Settlement

        data class Busy(val operations: Int) : Settlement

        data object Conflict : Settlement
    }

    enum class Resumption {
        Resumed,
        Conflict,
    }
}
