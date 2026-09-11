package io.github.amichne.kast.workspace.contract

/** Physical source identity under the admitted workspace model; no VFS refresh is implied. */
fun interface WorkspaceSourceObservationOperations {
    fun observe(workspace: PublishedWorkspace): WorkspaceSourceObservation
}

sealed interface WorkspaceSourceObservation {
    data class Observed(val identity: WorkspaceStateIdentity) : WorkspaceSourceObservation

    data object ModelInputsChanged : WorkspaceSourceObservation

    data object ModelInputsUnavailable : WorkspaceSourceObservation

    data object Unavailable : WorkspaceSourceObservation
}

/** Reuses the publication or acquires a successor, retaining synchronization's closed failures. */
fun interface WorkspaceReadinessOperations {
    fun ready(): IndexSynchronizationResult
}

/** Historical publication authorizes refreshing admitted roots, never a semantic read. */
sealed interface WorkspaceRefreshBasis {
    data class Available(val publication: PublishedWorkspace) : WorkspaceRefreshBasis

    data object Unavailable : WorkspaceRefreshBasis
}

fun interface WorkspaceRefreshBasisOperations {
    fun refreshBasis(): WorkspaceRefreshBasis
}
