package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.TransitionBlockerKind

/**
 * Physical effects required by the deterministic workspace transition coordinator.
 *
 * Implementations own settlement, IntelliJ refresh/import, candidate capture, and reconciliation.
 * They expose no live platform object to the service.
 */
interface WorkspaceTransitionOperations {
    fun settle(signals: Set<WorkspaceSignal>)

    fun refresh(signals: Set<WorkspaceSignal>)

    fun captureIdentity(): WorkspaceStateIdentity

    fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity
}

sealed interface WorkspaceTransitionFailureDisposition {
    data object Cancellation : WorkspaceTransitionFailureDisposition

    data class Retry(
        val detail: String,
    ) : WorkspaceTransitionFailureDisposition

    data class Blocked(
        val kind: TransitionBlockerKind,
        val detail: String,
    ) : WorkspaceTransitionFailureDisposition
}

fun interface WorkspaceTransitionFailureClassifier {
    /**
     * Boundary transition: `Throwable -> WorkspaceTransitionFailureDisposition`.
     *
     * Converts implementation cancellation, retry, and unexpected adapter failures into one
     * exhaustive service disposition. Raw exception types may be inspected only by the physical
     * composition adapter.
     */
    fun classify(failure: Throwable): WorkspaceTransitionFailureDisposition
}
