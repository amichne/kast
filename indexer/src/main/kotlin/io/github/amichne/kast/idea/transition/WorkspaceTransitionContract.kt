package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit

/**
 * Construction transition: `String -> WorkspaceStateIdentity`.
 *
 * Establishes a non-blank identity for the exact inputs observed around one
 * reconciliation. Raw extraction is permitted only when refining it to the
 * persisted [io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity].
 */
@JvmInline
internal value class WorkspaceStateIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Workspace state identity must not be blank" }
    }
}

internal enum class WorkspaceLifecycle {
    Ready,
    Dirty,
    Settling,
    Refreshing,
    Reconciling,
    Verifying,
    Blocked,
}

internal enum class WorkspaceSignal {
    Source,
    BuildSemantic,
    Configuration,
    Scope,
    SemanticEnvironment,
    GitWorktree,
    RecoveryProbe,
    RecoveryAudit,
}

internal enum class TransitionPhase {
    Settling,
    Refreshing,
    Reconciling,
    Verifying,
    Publishing,
}

internal data class TransitionBlocker(
    val phase: TransitionPhase,
    val detail: String,
) {
    init {
        require(detail.isNotBlank()) { "Transition blocker detail must not be blank" }
    }
}

/**
 * Observation transition:
 * `WorkspaceTransitionCoordinator state -> WorkspaceTransitionSnapshot`.
 *
 * [activeSourceFreshness] is exact only during refresh, reconciliation, or
 * verification; every inactive lifecycle carries [WorkspaceSourceFreshness.Absent].
 */
internal data class WorkspaceTransitionSnapshot(
    val lifecycle: WorkspaceLifecycle,
    val pendingSignals: Set<WorkspaceSignal>,
    val published: PublishedWorkspaceGenerationState,
    val blocker: TransitionBlocker?,
    val observedEventCount: Long,
    val activeSourceFreshness: WorkspaceSourceFreshness,
) {
    val isReady: Boolean
        get() = lifecycle == WorkspaceLifecycle.Ready && published is PublishedWorkspaceGenerationState.Published
}

internal enum class TransitionRun {
    NoWork,
    Published,
    Invalidated,
    Retry,
    Blocked,
}

internal sealed class WorkspaceTransitionRetryException(message: String) : IllegalStateException(message)

internal sealed interface GenerationPublication {
    data class Published(val commit: WorkspaceGenerationCommit) : GenerationPublication {
        val manifest: PublishedWorkspaceGenerationManifest
            get() = commit.manifest
    }

    data object InvalidatedBeforeCommit : GenerationPublication

    data class InvalidatedAfterCommit(
        val commit: WorkspaceGenerationCommit,
    ) : GenerationPublication {
        val manifest: PublishedWorkspaceGenerationManifest
            get() = commit.manifest
    }
}

/** Opaque ownership of an active workspace transaction before publication validation. */
internal interface OpenWorkspacePublication

/** Opaque proof that the active workspace transaction passed publication validation. */
internal interface PreparedWorkspacePublication

internal interface WorkspaceTransitionOperations {
    fun settle(signals: Set<WorkspaceSignal>)

    fun refresh(signals: Set<WorkspaceSignal>)

    fun captureIdentity(): WorkspaceStateIdentity

    /** Returns the identity of the inputs that were actually reconciled. */
    fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity

    /** Begins the SQLite transaction before reconciliation writes occur. */
    fun beginPublication(): OpenWorkspacePublication

    /**
     * Proof transition:
     * `(OpenWorkspacePublication, WorkspaceStateIdentity) -> PreparedWorkspacePublication`.
     *
     * Verifies completeness and binds the reconciled identity without
     * committing the transaction. The returned capability is the only input
     * accepted by [commitPublication].
     */
    fun preparePublication(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
    ): PreparedWorkspacePublication

    /** Commits the workspace facts and publication row in one SQLite transaction. */
    fun commitPublication(prepared: PreparedWorkspacePublication): GenerationPublication

    /** Rolls back an uncommitted workspace transaction. */
    fun discardPublication(open: OpenWorkspacePublication)

    /** Rolls back a validated but uncommitted workspace transaction. */
    fun discardPublication(prepared: PreparedWorkspacePublication)
}
