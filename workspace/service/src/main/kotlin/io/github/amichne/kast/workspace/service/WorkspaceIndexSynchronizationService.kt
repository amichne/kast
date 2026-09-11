package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.kernel.KastWorkspaceReadinessOutcome
import io.github.amichne.kast.workspace.contract.IndexSynchronizationFailure
import io.github.amichne.kast.workspace.contract.IndexSynchronizationOperations
import io.github.amichne.kast.workspace.contract.IndexSynchronizationResult
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspacePublicationRun
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSourceObservation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceObservationOperations

/** Publication effect following a completed physical refresh of one exact prior workspace. */
fun interface WorkspaceIndexPublicationOperations {
    fun publishAfterRefresh(prior: PublishedWorkspace): WorkspacePublicationRun
}

/** Refreshes admitted roots, waits at the physical boundary, then publishes observed evidence. */
class WorkspaceIndexSynchronizationService(
    private val workspaces: WorkspaceInspectionOperations,
    private val refresh: WorkspaceIndexRefreshOperations,
    private val publication: WorkspaceIndexPublicationOperations,
    private val sourceObservation: WorkspaceSourceObservationOperations = WorkspaceSourceObservationOperations {
        WorkspaceSourceObservation.Unavailable
    },
    private val transitions: WorkspaceTransitionOwner = WorkspaceTransitionOwner(),
    private val observability: io.github.amichne.kast.kernel.KastObservability =
        io.github.amichne.kast.kernel.KastObservability.Disabled,
    private val refreshBasis: io.github.amichne.kast.workspace.contract.WorkspaceRefreshBasisOperations =
        io.github.amichne.kast.workspace.contract.WorkspaceRefreshBasisOperations {
            when (val state = workspaces.inspect()) {
                is WorkspaceRuntimeState.Ready ->
                    io.github.amichne.kast.workspace.contract.WorkspaceRefreshBasis.Available(state.workspace)
                else -> io.github.amichne.kast.workspace.contract.WorkspaceRefreshBasis.Unavailable
            }
        },
) : IndexSynchronizationOperations, WorkspaceReadinessOperations {
    /** Physical observation gains either current publication evidence or a closed rejection. */
    override fun ready(): IndexSynchronizationResult = transitions.exclusively {
        when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready ->
                when (val observed = sourceObservation.observe(state.workspace)) {
                    is WorkspaceSourceObservation.Observed ->
                        if (observed.identity == state.workspace.sourceState) {
                            if (workspaces.inspect() == state) {
                                observability.observeWorkspaceReadiness(KastWorkspaceReadinessOutcome.REUSED)
                                IndexSynchronizationResult.Unchanged(state.workspace)
                            } else rejected(IndexSynchronizationFailure.PublicationInvalidated)
                        } else {
                            synchronizeExclusively()
                        }
                    WorkspaceSourceObservation.ModelInputsChanged ->
                        rejected(
                            IndexSynchronizationFailure.PublicationBlocked(
                                io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker.ModelInputsChanged
                            ),
                            KastWorkspaceReadinessOutcome.MODEL_INPUTS_CHANGED,
                        )
                    WorkspaceSourceObservation.ModelInputsUnavailable ->
                        rejected(
                            IndexSynchronizationFailure.PublicationBlocked(
                                io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
                                    .ModelInputsUnavailable
                            ),
                            KastWorkspaceReadinessOutcome.MODEL_INPUTS_UNAVAILABLE,
                        )
                    WorkspaceSourceObservation.Unavailable ->
                        rejected(
                            IndexSynchronizationFailure.PublicationBlocked(
                                io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
                                    .CandidateCaptureUnavailable
                            ),
                            KastWorkspaceReadinessOutcome.SOURCE_OBSERVATION_UNAVAILABLE,
                        )
                }
            WorkspaceRuntimeState.Reconciling,
            is WorkspaceRuntimeState.Blocked -> synchronizeExclusively()
            WorkspaceRuntimeState.Absent ->
                rejected(IndexSynchronizationFailure.WorkspaceNotReady, KastWorkspaceReadinessOutcome.WORKSPACE_ABSENT)
            WorkspaceRuntimeState.Starting ->
                rejected(
                    IndexSynchronizationFailure.WorkspaceNotReady,
                    KastWorkspaceReadinessOutcome.WORKSPACE_STARTING,
                )
            WorkspaceRuntimeState.Stopping ->
                rejected(
                    IndexSynchronizationFailure.WorkspaceNotReady,
                    KastWorkspaceReadinessOutcome.WORKSPACE_STOPPING,
                )
        }
    }

    override fun synchronize(): IndexSynchronizationResult = transitions.exclusively {
        synchronizeExclusively()
    }

    private fun synchronizeExclusively(): IndexSynchronizationResult {
        val prior =
            when (val state = workspaces.inspect()) {
                is WorkspaceRuntimeState.Ready -> state.workspace
                is WorkspaceRuntimeState.Blocked,
                WorkspaceRuntimeState.Reconciling ->
                    when (val basis = refreshBasis.refreshBasis()) {
                        is io.github.amichne.kast.workspace.contract.WorkspaceRefreshBasis.Available ->
                            basis.publication
                        io.github.amichne.kast.workspace.contract.WorkspaceRefreshBasis.Unavailable ->
                            return rejected(
                                IndexSynchronizationFailure.WorkspaceNotReady,
                                KastWorkspaceReadinessOutcome.REFRESH_BASIS_UNAVAILABLE,
                            )
                    }
                WorkspaceRuntimeState.Absent ->
                    return rejected(
                        IndexSynchronizationFailure.WorkspaceNotReady,
                        KastWorkspaceReadinessOutcome.WORKSPACE_ABSENT,
                    )
                WorkspaceRuntimeState.Starting ->
                    return rejected(
                        IndexSynchronizationFailure.WorkspaceNotReady,
                        KastWorkspaceReadinessOutcome.WORKSPACE_STARTING,
                    )
                WorkspaceRuntimeState.Stopping ->
                    return rejected(
                        IndexSynchronizationFailure.WorkspaceNotReady,
                        KastWorkspaceReadinessOutcome.WORKSPACE_STOPPING,
                    )
            }
        val refreshed =
            try {
                refresh.refresh(prior)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                observability.observeWorkspaceRefresh(
                    io.github.amichne.kast.kernel.KastWorkspaceRefreshOutcome.INTERRUPTED
                )
                throw cancelled
            }
        when (refreshed) {
            WorkspaceIndexRefresh.Refreshed ->
                observability.observeWorkspaceRefresh(
                    io.github.amichne.kast.kernel.KastWorkspaceRefreshOutcome.COMPLETED
                )
            is WorkspaceIndexRefresh.Rejected -> {
                observability.observeWorkspaceRefresh(
                    io.github.amichne.kast.kernel.KastWorkspaceRefreshOutcome.REJECTED
                )
                return rejected(IndexSynchronizationFailure.Refresh(refreshed.failure))
            }
        }
        return when (val published = publication.publishAfterRefresh(prior)) {
            is WorkspacePublicationRun.Published ->
                if (
                    published.workspace.root == prior.root &&
                        published.workspace.generation.value > prior.generation.value
                ) {
                    observability.observeWorkspaceReadiness(KastWorkspaceReadinessOutcome.PUBLISHED)
                    IndexSynchronizationResult.Synchronized(published.workspace)
                } else {
                    rejected(IndexSynchronizationFailure.PublicationContractViolation)
                }
            is WorkspacePublicationRun.Unchanged ->
                if (
                    published.workspace.readLease == prior.readLease &&
                        published.workspace.sourceState == prior.sourceState
                ) {
                    observability.observeWorkspaceReadiness(KastWorkspaceReadinessOutcome.REFRESHED_UNCHANGED)
                    IndexSynchronizationResult.Unchanged(published.workspace)
                } else {
                    rejected(IndexSynchronizationFailure.PublicationContractViolation)
                }
            WorkspacePublicationRun.Invalidated -> rejected(IndexSynchronizationFailure.PublicationInvalidated)
            is WorkspacePublicationRun.Blocked ->
                rejected(IndexSynchronizationFailure.PublicationBlocked(published.blocker))
            WorkspacePublicationRun.NoWork -> rejected(IndexSynchronizationFailure.PublicationContractViolation)
        }
    }

    private fun rejected(
        failure: IndexSynchronizationFailure,
        outcome: KastWorkspaceReadinessOutcome = failure.readinessOutcome(),
    ): IndexSynchronizationResult.Rejected {
        observability.observeWorkspaceReadiness(outcome)
        return IndexSynchronizationResult.Rejected(failure)
    }
}

/** Exhaustive finite projection: no failure details, workspace identities, or source payloads escape. */
private fun IndexSynchronizationFailure.readinessOutcome(): KastWorkspaceReadinessOutcome =
    when (this) {
        IndexSynchronizationFailure.WorkspaceNotReady -> KastWorkspaceReadinessOutcome.WORKSPACE_NOT_READY
        is IndexSynchronizationFailure.Refresh ->
            when (failure) {
                WorkspaceIndexRefreshFailure.INVALID_SOURCE_ROOT_SCOPE ->
                    KastWorkspaceReadinessOutcome.REFRESH_INVALID_SOURCE_ROOT_SCOPE
                WorkspaceIndexRefreshFailure.REFRESH_UNAVAILABLE -> KastWorkspaceReadinessOutcome.REFRESH_UNAVAILABLE
                WorkspaceIndexRefreshFailure.INDEXING_INTERRUPTED -> KastWorkspaceReadinessOutcome.INDEXING_INTERRUPTED
                WorkspaceIndexRefreshFailure.INDEXING_TIMED_OUT -> KastWorkspaceReadinessOutcome.INDEXING_TIMED_OUT
                WorkspaceIndexRefreshFailure.INDEXING_FAILED -> KastWorkspaceReadinessOutcome.INDEXING_FAILED
            }
        IndexSynchronizationFailure.PublicationInvalidated -> KastWorkspaceReadinessOutcome.PUBLICATION_INVALIDATED
        IndexSynchronizationFailure.PublicationContractViolation ->
            KastWorkspaceReadinessOutcome.PUBLICATION_CONTRACT_VIOLATION
        is IndexSynchronizationFailure.PublicationBlocked ->
            when (blocker) {
                WorkspacePublicationBlocker.ModelInputsChanged -> KastWorkspaceReadinessOutcome.MODEL_INPUTS_CHANGED
                WorkspacePublicationBlocker.ModelInputsUnavailable ->
                    KastWorkspaceReadinessOutcome.MODEL_INPUTS_UNAVAILABLE
                WorkspacePublicationBlocker.CandidateCaptureUnavailable ->
                    KastWorkspaceReadinessOutcome.CANDIDATE_CAPTURE_UNAVAILABLE
                WorkspacePublicationBlocker.ReconciliationUnavailable ->
                    KastWorkspaceReadinessOutcome.RECONCILIATION_UNAVAILABLE
                is WorkspacePublicationBlocker.IncompleteEvidence -> KastWorkspaceReadinessOutcome.INCOMPLETE_EVIDENCE
                WorkspacePublicationBlocker.PublicationUnavailable ->
                    KastWorkspaceReadinessOutcome.PUBLICATION_UNAVAILABLE
            }
    }
