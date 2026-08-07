package io.github.amichne.kast.idea

import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitFailure

internal enum class WorkspaceTransitionWaitFailureCode {
    DEADLINE_EXCEEDED,
    INGRESS_CLOSED,
    INTERRUPTED,
    FUTURE_FAILED,
    FUTURE_CANCELLED,
    ;

    companion object {
        /**
         * Proof transition:
         * `RuntimeProgressAwaitFailure -> WorkspaceTransitionWaitFailureCode`.
         *
         * Preserves the closed progress-wait failure identity until the
         * JSON-RPC conflict boundary serializes its enum name.
         */
        fun derive(failure: RuntimeProgressAwaitFailure): WorkspaceTransitionWaitFailureCode = when (failure) {
            is RuntimeProgressAwaitFailure.DeadlineExceeded -> DEADLINE_EXCEEDED
            is RuntimeProgressAwaitFailure.ProjectDisposed -> INGRESS_CLOSED
            is RuntimeProgressAwaitFailure.Interrupted -> INTERRUPTED
            is RuntimeProgressAwaitFailure.FutureFailed -> FUTURE_FAILED
            is RuntimeProgressAwaitFailure.FutureCancelled -> FUTURE_CANCELLED
        }
    }
}
