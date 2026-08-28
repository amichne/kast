package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompiler
import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompilerOpening
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
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Host-neutral exact publication retained for one admitted endpoint generation. */
class HostedWorkspaceOperations(
    private val workspace: PublishedWorkspace,
) : WorkspaceInspectionOperations, SemanticReadLeaseGuard {
    override fun inspect(): WorkspaceRuntimeState = WorkspaceRuntimeState.Ready(workspace)

    override fun <Value> whileCurrent(
        expected: SemanticReadLease,
        operation: () -> Value,
    ): SemanticReadLeaseUse<Value> = if (expected == workspace.readLease) {
        SemanticReadLeaseUse.Completed(operation())
    } else {
        SemanticReadLeaseUse.Moved
    }
}

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
