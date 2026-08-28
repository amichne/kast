package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompiler
import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompilerOpening
import io.github.amichne.kast.change.verify.ResultingGenerationPublication
import io.github.amichne.kast.change.verify.ResultingGenerationPublicationRejection
import io.github.amichne.kast.change.verify.ResultingGenerationPublisher
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.topology.build.TopologyBuildService
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyCandidateEnumerator
import io.github.amichne.kast.topology.contract.TopologyFileExtractor
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotReader
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseGuard
import io.github.amichne.kast.workspace.contract.SemanticReadLeaseUse
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspacePublicationSerialization
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.service.ResultingWorkspacePublication

/** One exact host-supplied attempt to move an admitted workspace publication forward. */
sealed interface HostedWorkspaceTransition {
    data class Published(
        val publication: ResultingWorkspacePublication,
    ) : HostedWorkspaceTransition

    data object Unchanged : HostedWorkspaceTransition

    data class Rejected(
        val blocker: WorkspacePublicationBlocker,
    ) : HostedWorkspaceTransition

    companion object {
        /**
         * Proof transition: `(PublishedWorkspace, PublishedWorkspace) ->
         * HostedWorkspaceTransition`.
         *
         * Preserves an identical publication as [Unchanged], admits only a same-root strictly
         * newer candidate as [Published], and closes every invalid candidate as [Rejected].
         */
        fun admit(
            current: PublishedWorkspace,
            candidate: PublishedWorkspace,
        ): HostedWorkspaceTransition {
            if (
                current.readLease == candidate.readLease &&
                current.sourceState == candidate.sourceState
            ) {
                return Unchanged
            }
            return when (val admitted = ResultingWorkspacePublication.admit(
                current.readLease,
                candidate,
            )) {
                is Refinement.Refined -> Published(admitted.value)
                is Refinement.Rejected -> Rejected(
                    WorkspacePublicationBlocker.PublicationUnavailable,
                )
            }
        }
    }
}

/** Narrow boundary that may only publish a proven successor of the supplied workspace. */
enum class HostedWorkspaceTransitionCause {
    OBSERVED_SOURCE_EVENT,
    PROVEN_SOURCE_TRANSITION,
}

fun interface HostedWorkspaceTransitionOperations {
    fun publishAfter(
        current: PublishedWorkspace,
        cause: HostedWorkspaceTransitionCause,
    ): HostedWorkspaceTransition
}

/** Host-neutral exact publication retained for one admitted endpoint generation. */
class HostedWorkspaceOperations(
    workspace: PublishedWorkspace,
    private val transition: HostedWorkspaceTransitionOperations =
        HostedWorkspaceTransitionOperations { _, _ -> HostedWorkspaceTransition.Unchanged },
    private val serialization: WorkspacePublicationSerialization =
        WorkspacePublicationSerialization(),
) : WorkspaceInspectionOperations, SemanticReadLeaseGuard, HostedMutationPublicationOperations {
    @Volatile
    private var state: WorkspaceRuntimeState = WorkspaceRuntimeState.Ready(workspace)

    override fun inspect(): WorkspaceRuntimeState = serialization.serialized {
        refresh(HostedWorkspaceTransitionCause.OBSERVED_SOURCE_EVENT)
    }

    override fun <Value> whileCurrent(
        expected: SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = serialization.serialized {
        when (val current = refresh(HostedWorkspaceTransitionCause.OBSERVED_SOURCE_EVENT)) {
            is WorkspaceRuntimeState.Ready -> if (expected == current.workspace.readLease) {
                SemanticReadLeaseUse.Completed(operation())
            } else {
                SemanticReadLeaseUse.Moved
            }
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
            -> SemanticReadLeaseUse.Moved
        }
    }

    override fun publishAfter(
        prior: SemanticReadLease,
    ): ResultingGenerationPublication = serialization.serialized {
        val current = state as? WorkspaceRuntimeState.Ready
            ?: return@serialized publicationRejected(
                ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE,
            )
        if (current.workspace.readLease != prior) {
            return@serialized publicationRejected(
                ResultingGenerationPublicationRejection.RECONCILIATION_INVALIDATED,
            )
        }
        val observed = refresh(HostedWorkspaceTransitionCause.OBSERVED_SOURCE_EVENT)
        val refreshed = if (
            observed is WorkspaceRuntimeState.Ready &&
            observed.workspace.readLease == prior
        ) {
            refresh(HostedWorkspaceTransitionCause.PROVEN_SOURCE_TRANSITION)
        } else {
            observed
        }
        when (refreshed) {
            is WorkspaceRuntimeState.Ready -> if (
                refreshed.workspace.root == prior.workspaceRoot &&
                refreshed.workspace.generation.value > prior.generation.value
            ) {
                ResultingGenerationPublication.Published(refreshed.workspace)
            } else {
                publicationRejected(
                    ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE,
                )
            }
            is WorkspaceRuntimeState.Blocked -> publicationRejected(
                ResultingGenerationPublicationRejection.RECONCILIATION_BLOCKED,
            )
            WorkspaceRuntimeState.Absent,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
            -> publicationRejected(
                ResultingGenerationPublicationRejection.RECONCILIATION_INVALIDATED,
            )
        }
    }

    override fun publishCurrentTransition(): ResultingGenerationPublication =
        serialization.serialized {
            val observed = refresh(HostedWorkspaceTransitionCause.OBSERVED_SOURCE_EVENT)
            val current = observed as? WorkspaceRuntimeState.Ready
                ?: return@serialized when (observed) {
                    is WorkspaceRuntimeState.Blocked -> publicationRejected(
                        ResultingGenerationPublicationRejection.RECONCILIATION_BLOCKED,
                    )
                    else -> publicationRejected(
                        ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE,
                    )
                }
            val prior = current.workspace.readLease
            when (val advanced = refresh(
                HostedWorkspaceTransitionCause.PROVEN_SOURCE_TRANSITION,
            )) {
                is WorkspaceRuntimeState.Ready -> if (
                    advanced.workspace.root == prior.workspaceRoot &&
                    advanced.workspace.generation.value > prior.generation.value
                ) {
                    ResultingGenerationPublication.Published(advanced.workspace)
                } else {
                    publicationRejected(
                        ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE,
                    )
                }
                is WorkspaceRuntimeState.Blocked -> publicationRejected(
                    ResultingGenerationPublicationRejection.RECONCILIATION_BLOCKED,
                )
                WorkspaceRuntimeState.Absent,
                WorkspaceRuntimeState.Reconciling,
                WorkspaceRuntimeState.Starting,
                WorkspaceRuntimeState.Stopping,
                -> publicationRejected(
                    ResultingGenerationPublicationRejection.RECONCILIATION_INVALIDATED,
                )
            }
        }

    private fun refresh(cause: HostedWorkspaceTransitionCause): WorkspaceRuntimeState {
        val current = state as? WorkspaceRuntimeState.Ready ?: return state
        state = try {
            when (val publication = transition.publishAfter(current.workspace, cause)) {
                is HostedWorkspaceTransition.Published -> if (
                    publication.publication.prior == current.workspace.readLease
                ) {
                    WorkspaceRuntimeState.Ready(publication.publication.workspace)
                } else {
                    WorkspaceRuntimeState.Blocked(
                        WorkspacePublicationBlocker.PublicationUnavailable,
                    )
                }
                HostedWorkspaceTransition.Unchanged -> current
                is HostedWorkspaceTransition.Rejected ->
                    WorkspaceRuntimeState.Blocked(publication.blocker)
            }
        } catch (_: RuntimeException) {
            WorkspaceRuntimeState.Blocked(WorkspacePublicationBlocker.PublicationUnavailable)
        }
        return state
    }
}

private fun publicationRejected(
    reason: ResultingGenerationPublicationRejection,
): ResultingGenerationPublication = ResultingGenerationPublication.Rejected(reason)

data class HostedTopologyRuntimePorts(
    val candidates: TopologyCandidateEnumerator,
    val extractor: TopologyFileExtractor,
    val snapshots: TopologySnapshotStore,
)

/** The thin hosted topology surface; no generic graph operation is exposed. */
class HostedTopologyOperations internal constructor(
    val build: TopologyBuildOperations,
    val traversal: TraversalOperations,
)

object HostedTopologyComposition {
    fun create(
        workspaces: HostedWorkspaceOperations,
        ports: HostedTopologyRuntimePorts,
    ): HostedTopologyOperations = HostedTopologyOperations(
        build = TopologyBuildService.create(
            workspaces,
            workspaces,
            ports.candidates,
            ports.extractor,
            ports.snapshots,
        ),
        traversal = HostedTopologyBackedTraversalOperations(
            workspaces,
            ports.snapshots,
            ports.snapshots,
        ),
    )
}

/** Public traversal router whose sole graph backend is an eligible durable SQLite snapshot. */
private class HostedTopologyBackedTraversalOperations(
    private val workspaces: WorkspaceInspectionOperations,
    private val snapshotReader: TopologySnapshotReader,
    private val contentReader: TopologySnapshotContentReader,
) : TraversalOperations {
    override suspend fun run(plan: TraversalPlan): TraversalResult {
        val workspace = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
            -> return rejected(
                TraversalRejection.OneHopRejected(RelationReadRejection.WORKSPACE_NOT_READY),
            )
        }
        if (plan.start.lease != workspace.readLease) {
            return rejected(
                TraversalRejection.OneHopRejected(RelationReadRejection.STALE_GENERATION),
            )
        }
        val snapshot = when (
            val eligible = snapshotReader.eligible(TopologyWorkspaceIdentity.from(workspace))
        ) {
            is TopologySnapshotEligibility.Eligible -> eligible.snapshot
            is TopologySnapshotEligibility.Stale ->
                return rejected(TraversalRejection.RequiredEvidenceStale)
            TopologySnapshotEligibility.Unavailable,
            is TopologySnapshotEligibility.Rejected,
            -> return rejected(TraversalRejection.RequiredEvidenceUnavailable)
        }
        val compiler = when (val opened = SqliteTopologyRelationCompiler.open(
            snapshot,
            contentReader,
        )) {
            is SqliteTopologyRelationCompilerOpening.Opened -> opened.compiler
            is SqliteTopologyRelationCompilerOpening.Rejected ->
                return rejected(TraversalRejection.RequiredEvidenceUnavailable)
        }
        val relations = RelationService(workspaces, compiler)
        return traversalOperations(relations).run(plan)
    }
}

private fun rejected(reason: TraversalRejection): TraversalResult = TraversalResult.Rejected(reason)
