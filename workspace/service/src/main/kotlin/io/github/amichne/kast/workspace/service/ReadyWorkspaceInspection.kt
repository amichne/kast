package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.workspace.contract.IndexSynchronizationFailure
import io.github.amichne.kast.workspace.contract.IndexSynchronizationResult
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Semantic inspection carries readiness proof forward; passive inspection uses the publication owner. */
class ReadyWorkspaceInspection(private val readiness: WorkspaceReadinessOperations) : WorkspaceInspectionOperations {
    override fun inspect(): WorkspaceRuntimeState = when (val result = readiness.ready()) {
        is IndexSynchronizationResult.Unchanged -> WorkspaceRuntimeState.Ready(result.workspace)
        is IndexSynchronizationResult.Synchronized -> WorkspaceRuntimeState.Ready(result.workspace)
        is IndexSynchronizationResult.Rejected -> WorkspaceRuntimeState.Blocked(
            when (val failure = result.failure) {
                is IndexSynchronizationFailure.PublicationBlocked -> failure.blocker
                IndexSynchronizationFailure.WorkspaceNotReady,
                is IndexSynchronizationFailure.Refresh,
                IndexSynchronizationFailure.PublicationInvalidated,
                IndexSynchronizationFailure.PublicationContractViolation,
                    -> WorkspacePublicationBlocker.ReconciliationUnavailable
            },
        )
    }
}
