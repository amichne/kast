package io.github.amichne.kast.runtime.hosted.lifecycle

/**
 * The application owns admission across project requests and workspace preparation. It also owns the idle clock: the
 * Host is idle only while admission is open, no lease is active, and no native work has been observed since.
 */
internal class HostedApplicationAdmission(private val nanoTime: () -> Long = System::nanoTime) {
    private sealed interface State {
        data object Open : State

        data class Fenced(val transaction: String) : State
    }

    private var state: State = State.Open
    private val active = mutableSetOf<Lease>()
    private var idleSinceNanos: Long = nanoTime()

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
                    // Time spent fenced never counts toward a later idle shutdown.
                    idleSinceNanos = nanoTime()
                    Resumption.Resumed
                } else Resumption.Conflict
        }

    @Synchronized fun fencedAndIdle(): Boolean = state is State.Fenced && active.isEmpty()

    @Synchronized fun settledFor(transaction: String): Boolean = state == State.Fenced(transaction) && active.isEmpty()

    /** Native work outside admission, such as import or indexing, restarts the idle clock. */
    @Synchronized
    fun observeNativeWork() {
        if (state == State.Open && active.isEmpty()) idleSinceNanos = nanoTime()
    }

    /** Whether [fenceIfIdle] would fence now; it decides nothing, so the caller can inspect before fencing. */
    @Synchronized
    fun idleFor(limitNanos: Long): Boolean =
        state == State.Open && active.isEmpty() && nanoTime() - idleSinceNanos >= limitNanos

    /**
     * Fences admission for [transaction] only when the Host has been idle for at least [limitNanos]. The decision and
     * the fence happen under one lock, so no request can be admitted after an idle decision.
     */
    @Synchronized
    fun fenceIfIdle(transaction: String, limitNanos: Long): IdleFence =
        when {
            state != State.Open -> IdleFence.AlreadyFenced
            active.isNotEmpty() -> IdleFence.Active
            nanoTime() - idleSinceNanos < limitNanos -> IdleFence.NotDue
            else -> {
                state = State.Fenced(transaction)
                IdleFence.Fenced
            }
        }

    @Synchronized
    private fun release(lease: Lease) {
        check(active.remove(lease)) { "Host operation lease already completed" }
        if (active.isEmpty()) idleSinceNanos = nanoTime()
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

    enum class IdleFence {
        Fenced,
        NotDue,
        Active,
        AlreadyFenced,
    }
}
