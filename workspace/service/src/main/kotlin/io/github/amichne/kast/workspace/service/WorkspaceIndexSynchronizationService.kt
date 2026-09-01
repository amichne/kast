package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.workspace.contract.IndexSynchronizationFailure
import io.github.amichne.kast.workspace.contract.IndexSynchronizationOperations
import io.github.amichne.kast.workspace.contract.IndexSynchronizationResult
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Publication effect following a completed physical refresh of one exact prior workspace. */
fun interface WorkspaceIndexPublicationOperations {
    fun publishAfterRefresh(prior: PublishedWorkspace): WorkspacePublicationRun
}

/** Refreshes admitted roots, waits at the physical boundary, then publishes observed evidence. */
class WorkspaceIndexSynchronizationService(
    private val workspaces: WorkspaceInspectionOperations,
    private val refresh: WorkspaceIndexRefreshOperations,
    private val publication: WorkspaceIndexPublicationOperations,
) : IndexSynchronizationOperations {
    override fun synchronize(): IndexSynchronizationResult {
        val prior = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return rejected(IndexSynchronizationFailure.WorkspaceNotReady)
        }
        when (val refreshed = refresh.refresh(prior)) {
            WorkspaceIndexRefresh.Refreshed -> Unit
            is WorkspaceIndexRefresh.Rejected -> return rejected(
                IndexSynchronizationFailure.Refresh(refreshed.failure),
            )
        }
        return when (val published = publication.publishAfterRefresh(prior)) {
            is WorkspacePublicationRun.Published -> if (
                published.workspace.root == prior.root &&
                published.workspace.generation.value > prior.generation.value
            ) {
                IndexSynchronizationResult.Synchronized(published.workspace)
            } else {
                rejected(IndexSynchronizationFailure.PublicationContractViolation)
            }
            is WorkspacePublicationRun.Unchanged -> if (
                published.workspace.readLease == prior.readLease &&
                published.workspace.sourceState == prior.sourceState
            ) {
                IndexSynchronizationResult.Unchanged(published.workspace)
            } else {
                rejected(IndexSynchronizationFailure.PublicationContractViolation)
            }
            WorkspacePublicationRun.Invalidated ->
                rejected(IndexSynchronizationFailure.PublicationInvalidated)
            is WorkspacePublicationRun.Blocked -> rejected(
                IndexSynchronizationFailure.PublicationBlocked(published.blocker),
            )
            WorkspacePublicationRun.NoWork ->
                rejected(IndexSynchronizationFailure.PublicationContractViolation)
        }
    }
}

private fun rejected(
    failure: IndexSynchronizationFailure,
): IndexSynchronizationResult.Rejected = IndexSynchronizationResult.Rejected(failure)
