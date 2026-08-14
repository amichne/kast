package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.TransitionBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceLifecycle
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot

enum class WorkspaceTransitionWaitFailure {
    DeadlineExceeded,
    RuntimeDisposed,
    Interrupted,
    AwaitCancelled,
    AwaitFailed,
}

sealed interface WorkspaceTransitionFailure {
    data object NotAttached : WorkspaceTransitionFailure

    data object Closed : WorkspaceTransitionFailure

    data class SemanticAdmissionFailed(
        val detail: String,
    ) : WorkspaceTransitionFailure

    data class Blocked(
        val blocker: TransitionBlocker,
    ) : WorkspaceTransitionFailure

    data class InvalidCompletion(
        val lifecycle: WorkspaceLifecycle,
    ) : WorkspaceTransitionFailure

    data class WaitRejected(
        val reason: WorkspaceTransitionWaitFailure,
        val stage: String,
        val elapsedMillis: Long,
        val noProgressMillis: Long,
    ) : WorkspaceTransitionFailure
}

sealed interface WorkspaceTransitionOutcome {
    data class Published(
        val publication: PublishedWorkspaceGeneration,
    ) : WorkspaceTransitionOutcome

    data class Rejected(
        val failure: WorkspaceTransitionFailure,
    ) : WorkspaceTransitionOutcome
}

sealed interface WorkspaceMutationTransitionFailure {
    data class AdmissionUnavailable(
        val state: WorkspaceMutationAdmissionState,
    ) : WorkspaceMutationTransitionFailure

    data class AdmissionMoved(
        val expectedRevision: Long,
        val actualRevision: Long,
    ) : WorkspaceMutationTransitionFailure

    data class ReconciliationRejected(
        val failure: WorkspaceTransitionFailure,
    ) : WorkspaceMutationTransitionFailure
}

enum class WorkspaceMutationAdmissionState {
    Pending,
    Failed,
}

sealed interface WorkspaceMutationTransitionOutcome<out Value> {
    data class Completed<Value>(
        val value: Value,
        val publication: PublishedWorkspaceGeneration,
    ) : WorkspaceMutationTransitionOutcome<Value>

    data class Rejected(
        val failure: WorkspaceMutationTransitionFailure,
    ) : WorkspaceMutationTransitionOutcome<Nothing>
}

/**
 * Narrow request authority for the single workspace transition owner.
 *
 * The port exposes no worker, live project, runtime, backend, or persistence capability.
 */
interface WorkspaceTransitionPort {
    suspend fun reconcile(request: WorkspaceTransitionRequest): WorkspaceTransitionOutcome

    suspend fun <Value> mutate(
        signal: WorkspaceSignal,
        detail: String,
        operation: suspend () -> Value,
    ): WorkspaceMutationTransitionOutcome<Value>
}

fun interface WorkspaceTransitionInspectionAuthority {
    /**
     * Observation transition:
     * `WorkspaceTransitionInspectionAuthority -> WorkspaceTransitionSnapshot`.
     *
     * Produces detached state from the single transition owner. No live implementation handle may
     * be retained by the snapshot.
     */
    fun inspect(): WorkspaceTransitionSnapshot
}

fun interface WorkspaceTransitionEventSink {
    fun observe(request: WorkspaceTransitionRequest)
}
