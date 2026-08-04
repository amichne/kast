package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit

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

internal data class WorkspaceTransitionSnapshot(
    val lifecycle: WorkspaceLifecycle,
    val pendingSignals: Set<WorkspaceSignal>,
    val published: PublishedWorkspaceGenerationManifest?,
    val blocker: TransitionBlocker?,
    val observedEventCount: Long,
    val publicationWarning: WorkspaceGenerationCommit.DurabilityUncertain? = null,
) {
    val isReady: Boolean
        get() = lifecycle == WorkspaceLifecycle.Ready && published != null
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

/** Opaque evidence that one immutable generation is prepared but not published. */
internal interface PreparedWorkspacePublication

internal interface WorkspaceTransitionOperations {
    fun settle(signals: Set<WorkspaceSignal>)

    fun refresh(signals: Set<WorkspaceSignal>)

    fun captureIdentity(): WorkspaceStateIdentity

    /** Returns the identity of the inputs that were actually reconciled. */
    fun reconcile(candidate: WorkspaceStateIdentity): WorkspaceStateIdentity

    /** Performs the slow immutable export without changing the current pointer. */
    fun preparePublication(identity: WorkspaceStateIdentity): PreparedWorkspacePublication

    /** Performs the bounded current-pointer commit. */
    fun commitPublication(prepared: PreparedWorkspacePublication): GenerationPublication

    /** Removes an unpublished immutable candidate. */
    fun discardPublication(prepared: PreparedWorkspacePublication)
}
