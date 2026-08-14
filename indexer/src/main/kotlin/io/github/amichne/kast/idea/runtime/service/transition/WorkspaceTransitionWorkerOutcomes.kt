package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionInspectionException
import io.github.amichne.kast.idea.transition.GitWorktreeTransitionStatus
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRetryException
import io.github.amichne.kast.workspace.contract.TransitionBlockerKind
import io.github.amichne.kast.workspace.contract.TransitionBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailureDisposition
import java.util.concurrent.CancellationException

internal enum class WorkspaceWorkerWaitOutcome {
    Continue,
    Interrupted,
}

internal class BuildSemanticInputsMovedDuringRefreshException(
    val before: BuildSemanticInputIdentity,
    val after: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs moved during Gradle refresh")

internal class BuildSemanticModelStaleException(
    val imported: BuildSemanticInputIdentity,
    val current: BuildSemanticInputIdentity,
) : IllegalStateException("Build-semantic inputs do not match the imported Gradle model")

internal fun classifyWorkspaceTransitionFailure(
    failure: Throwable,
): WorkspaceTransitionFailureDisposition {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    return when (failure) {
        is InterruptedException,
        is CancellationException,
        is ProcessCanceledException,
            -> WorkspaceTransitionFailureDisposition.Cancellation
        is WorkspaceTransitionRetryException ->
            WorkspaceTransitionFailureDisposition.Retry(failure.transitionDetail())
        else -> WorkspaceTransitionFailureDisposition.Blocked(
            kind = if (failure is GitWorktreeTransitionInspectionException) {
                TransitionBlockerKind.GitWorktreeInspectionUnavailable
            } else {
                TransitionBlockerKind.AdapterFailure
            },
            detail = failure.transitionDetail(),
        )
    }
}

private fun Throwable.transitionDetail(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.qualifiedName.orEmpty()

internal fun TransitionBlocker.toWorkerFailure(): Throwable = when (kind) {
    TransitionBlockerKind.GitWorktreeInspectionUnavailable ->
        GitWorktreeTransitionInspectionException(GitWorktreeTransitionStatus.Unavailable(detail))
    TransitionBlockerKind.AdapterFailure,
    TransitionBlockerKind.RetryableTransition,
        -> IllegalStateException(detail)
}

internal sealed interface RecoveryAuditOutcome {
    data object Current : RecoveryAuditOutcome

    sealed interface Drift : RecoveryAuditOutcome {
        val signal: WorkspaceSignal
        val dirtyReason: String
    }

    data object WorkspaceDrift : Drift {
        override val signal: WorkspaceSignal = WorkspaceSignal.RecoveryAudit
        override val dirtyReason: String = "workspace recovery audit requires reconciliation"
    }

    data object BuildSemanticDrift : Drift {
        override val signal: WorkspaceSignal = WorkspaceSignal.BuildSemantic
        override val dirtyReason: String = "workspace recovery audit found build-semantic drift"
    }
}
