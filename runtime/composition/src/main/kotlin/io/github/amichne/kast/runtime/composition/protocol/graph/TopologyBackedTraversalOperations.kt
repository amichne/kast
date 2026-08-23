package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompiler
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.service.traversalOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState

/** Public traversal router whose only repository graph backend is an eligible SQLite snapshot. */
internal class TopologyBackedTraversalOperations(
    private val workspaces: WorkspaceInspectionOperations,
    private val snapshots: TopologySnapshotStore,
) : TraversalOperations {
    override suspend fun run(plan: TraversalPlan): TraversalResult {
        val workspace = when (val state = workspaces.inspect()) {
            is WorkspaceRuntimeState.Ready -> state.workspace
            WorkspaceRuntimeState.Absent,
            is WorkspaceRuntimeState.Blocked,
            WorkspaceRuntimeState.Reconciling,
            WorkspaceRuntimeState.Starting,
            WorkspaceRuntimeState.Stopping,
                -> return rejected(RelationReadRejection.WORKSPACE_NOT_READY)
        }
        if (plan.start.lease != workspace.readLease) {
            return rejected(RelationReadRejection.STALE_GENERATION)
        }
        val snapshot = when (val eligible = snapshots.eligible(TopologyWorkspaceIdentity.from(workspace))) {
            is TopologySnapshotEligibility.Eligible -> eligible.snapshot
            is TopologySnapshotEligibility.Stale ->
                return rejected(RelationReadRejection.STALE_GENERATION)
            TopologySnapshotEligibility.Unavailable,
            is TopologySnapshotEligibility.Rejected,
                -> return rejected(RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE)
        }
        val relations = RelationService(
            workspaces,
            SqliteTopologyRelationCompiler(snapshot, snapshots),
        )
        return traversalOperations(relations).run(plan)
    }
}

private fun rejected(reason: RelationReadRejection): TraversalResult =
    TraversalResult.Rejected(TraversalRejection.OneHopRejected(reason))
