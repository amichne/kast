package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceFreshness
import io.github.amichne.kast.workspace.contract.TransitionBlocker
import io.github.amichne.kast.workspace.contract.TransitionBlockerKind
import io.github.amichne.kast.workspace.contract.TransitionPhase
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailureDisposition

internal data class TransitionCycle(
    val signals: Set<WorkspaceSignal>,
    val observedEventCount: Long,
    val sourceFreshness: WorkspaceSourceFreshness,
)

/**
 * Effect transition: `(() -> T) -> Result<T>`.
 *
 * Retains adapter failure for classification by the injected workspace failure authority.
 */
internal fun <T> runTransitionEffect(operation: () -> T): Result<T> = runCatching(operation)

internal fun WorkspaceTransitionFailureDisposition.toBlocker(
    phase: TransitionPhase,
    failure: Throwable,
): TransitionBlocker = when (this) {
    WorkspaceTransitionFailureDisposition.Cancellation -> throw failure
    is WorkspaceTransitionFailureDisposition.Retry -> TransitionBlocker(
        phase = phase,
        kind = TransitionBlockerKind.RetryableTransition,
        detail = detail,
    )
    is WorkspaceTransitionFailureDisposition.Blocked -> TransitionBlocker(
        phase = phase,
        kind = kind,
        detail = detail,
    )
}
