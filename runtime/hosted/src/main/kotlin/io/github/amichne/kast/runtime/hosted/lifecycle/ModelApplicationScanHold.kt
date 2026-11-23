package io.github.amichne.kast.runtime.hosted.lifecycle

import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/** The platform queue effects that one import owner may apply. */
internal interface ScanQueue {
    fun suspendQueue()

    fun resumeQueue()
}

/** Bounded evidence for one interval; the platform executor polls without parking for this long. */
@Serializable internal data class ModelApplicationScanObservation(val heldMillis: Long)

/**
 * Hold scans only while a resolved Gradle model is applied, so its root changes produce one scan.
 *
 * The IDEA 262 scan executor does not park while its queue is suspended with a pending task: it clears its running flag
 * to `hasQueuedTasks` and retries at once. A hold across Gradle resolution therefore spends processor time that Gradle
 * needs, for as long as resolution runs.
 */
internal class ModelApplicationScanHold(
    private val queue: ScanQueue,
    private val nanoTime: () -> Long = System::nanoTime,
    private val observe: (ModelApplicationScanObservation) -> Unit,
) {
    private sealed interface State {
        data object Idle : State

        data class Held(val sinceNanos: Long) : State

        data object Released : State
    }

    private var state: State = State.Idle

    @Synchronized
    fun applicationStarted() {
        if (state != State.Idle) return
        state = State.Held(nanoTime())
        queue.suspendQueue()
    }

    @Synchronized
    fun applicationSettled() {
        val held = state as? State.Held ?: return
        state = State.Idle
        resume(held)
    }

    /** Terminal: a late import signal cannot suspend the queue after its owner closes. */
    @Synchronized
    fun release() {
        val held = state as? State.Held
        state = State.Released
        held?.let(::resume)
    }

    private fun resume(held: State.Held) {
        queue.resumeQueue()
        observe(ModelApplicationScanObservation(TimeUnit.NANOSECONDS.toMillis(nanoTime() - held.sinceNanos)))
    }
}
