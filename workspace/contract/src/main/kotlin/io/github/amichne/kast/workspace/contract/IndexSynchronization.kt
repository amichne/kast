package io.github.amichne.kast.workspace.contract

/** Finite physical failures while refreshing admitted roots and waiting for index readiness. */
enum class WorkspaceIndexRefreshFailure {
    INVALID_SOURCE_ROOT_SCOPE,
    REFRESH_UNAVAILABLE,
    INDEXING_INTERRUPTED,
    INDEXING_TIMED_OUT,
    INDEXING_FAILED,
}
/** Closed result of the physical IntelliJ refresh/readiness boundary. */
sealed interface WorkspaceIndexRefresh {
    data object Refreshed : WorkspaceIndexRefresh

    data class Rejected(
        val failure: WorkspaceIndexRefreshFailure,
    ) : WorkspaceIndexRefresh
}

/** Physical effect restricted to source roots already carried by a published workspace. */
fun interface WorkspaceIndexRefreshOperations {
    fun refresh(workspace: PublishedWorkspace): WorkspaceIndexRefresh
}

/** Finite failures from the complete refresh and publication orchestration. */
sealed interface IndexSynchronizationFailure {
    data object WorkspaceNotReady : IndexSynchronizationFailure

    data class Refresh(
        val failure: WorkspaceIndexRefreshFailure,
    ) : IndexSynchronizationFailure

    data object PublicationInvalidated : IndexSynchronizationFailure

    data class PublicationBlocked(
        val blocker: WorkspacePublicationBlocker,
    ) : IndexSynchronizationFailure

    data object PublicationContractViolation : IndexSynchronizationFailure
}

/** A synchronized workspace is either a proven successor or the exact unchanged publication. */
sealed interface IndexSynchronizationResult {
    data class Synchronized(
        val workspace: PublishedWorkspace,
    ) : IndexSynchronizationResult

    data class Unchanged(
        val workspace: PublishedWorkspace,
    ) : IndexSynchronizationResult

    data class Rejected(
        val failure: IndexSynchronizationFailure,
    ) : IndexSynchronizationResult
}

/** Public domain operation shared by manual invocation and successful-apply scheduling. */
fun interface IndexSynchronizationOperations {
    fun synchronize(): IndexSynchronizationResult
}
