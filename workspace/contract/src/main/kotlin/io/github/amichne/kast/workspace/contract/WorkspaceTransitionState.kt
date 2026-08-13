package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.EvidenceGeneration

enum class WorkspaceLifecycle {
    Ready,
    Dirty,
    Settling,
    Refreshing,
    Reconciling,
    Verifying,
    Blocked,
}

enum class TransitionPhase {
    Settling,
    Refreshing,
    Reconciling,
    Verifying,
    Publishing,
}

enum class TransitionBlockerKind {
    RetryableTransition,
    GitWorktreeInspectionUnavailable,
    AdapterFailure,
}

/**
 * Finite phase and detached diagnostic for one blocked transition.
 */
data class TransitionBlocker(
    val phase: TransitionPhase,
    val kind: TransitionBlockerKind,
    val detail: String,
) {
    init {
        require(detail.isNotBlank()) { "Transition blocker detail must not be blank" }
    }
}

/**
 * Detached proof of one atomically published workspace identity and evidence generation.
 */
data class PublishedWorkspaceGeneration(
    val generation: EvidenceGeneration,
    val identity: WorkspaceStateIdentity,
)

sealed interface PublishedWorkspaceGenerationState {
    data object Unpublished : PublishedWorkspaceGenerationState

    data class Published(
        val publication: PublishedWorkspaceGeneration,
    ) : PublishedWorkspaceGenerationState
}

/**
 * Detached observation of the single workspace transition owner.
 *
 * [activeSourceFreshness] is exact only during refresh, reconciliation, or verification; every
 * inactive lifecycle carries [WorkspaceSourceFreshness.Absent].
 */
data class WorkspaceTransitionSnapshot(
    val lifecycle: WorkspaceLifecycle,
    val pendingSignals: Set<WorkspaceSignal>,
    val published: PublishedWorkspaceGenerationState,
    val blocker: TransitionBlocker?,
    val observedEventCount: Long,
    val activeSourceFreshness: WorkspaceSourceFreshness,
) {
    val isReady: Boolean
        get() = lifecycle == WorkspaceLifecycle.Ready &&
                published is PublishedWorkspaceGenerationState.Published
}

enum class TransitionRun {
    NoWork,
    Published,
    Invalidated,
    Retry,
    Blocked,
}
