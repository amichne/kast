package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.kernel.Refinement

enum class EdtHeartbeatTimeoutFailure {
    NOT_POSITIVE,
    EXCEEDS_MAXIMUM,
}

/**
 * Maximum local wait for one IntelliJ event-dispatch-thread heartbeat.
 */
@JvmInline
value class EdtHeartbeatTimeout private constructor(
    val milliseconds: Long,
) {
    companion object {
        const val MAXIMUM_MILLISECONDS: Long = 10_000

        /**
         * Proof transition:
         * <code>Long -> Refinement&lt;EdtHeartbeatTimeout, EdtHeartbeatTimeoutFailure&gt;</code>.
         *
         * Establishes a positive heartbeat timeout no greater than ten seconds. The finite
         * [EdtHeartbeatTimeoutFailure] rejects non-positive or excessive request-boundary values.
         * Raw milliseconds may be extracted only at the physical timed-wait boundary.
         */
        fun parse(milliseconds: Long): Refinement<EdtHeartbeatTimeout, EdtHeartbeatTimeoutFailure> = when {
            milliseconds <= 0 ->
                Refinement.Rejected(EdtHeartbeatTimeoutFailure.NOT_POSITIVE)
            milliseconds > MAXIMUM_MILLISECONDS ->
                Refinement.Rejected(EdtHeartbeatTimeoutFailure.EXCEEDS_MAXIMUM)
            else -> Refinement.Refined(EdtHeartbeatTimeout(milliseconds))
        }

        fun standard(): EdtHeartbeatTimeout = EdtHeartbeatTimeout(500)
    }
}

/**
 * Finite reasons the isolated IntelliJ runtime cannot admit work.
 */
sealed interface RuntimeLivenessFailure {
    data class FrozenEventDispatchThread(
        val timeout: EdtHeartbeatTimeout,
    ) : RuntimeLivenessFailure

    data object RuntimeDisposed : RuntimeLivenessFailure

    data object ProbeInterrupted : RuntimeLivenessFailure

    data object ProbeUnavailable : RuntimeLivenessFailure
}

sealed interface RuntimeLivenessAdmission {
    data object Live : RuntimeLivenessAdmission

    data class Rejected(
        val failure: RuntimeLivenessFailure,
    ) : RuntimeLivenessAdmission
}

fun interface RuntimeLivenessAuthority {
    /**
     * Proof transition: <code>RuntimeLivenessAuthority -> RuntimeLivenessAdmission</code>.
     *
     * Establishes that the runtime and its event-dispatch thread responded within a bounded local
     * deadline. [RuntimeLivenessFailure] is the closed expected failure. Raw project state,
     * scheduling, interruption, and timed waiting remain inside the physical adapter.
     */
    fun admit(): RuntimeLivenessAdmission
}
