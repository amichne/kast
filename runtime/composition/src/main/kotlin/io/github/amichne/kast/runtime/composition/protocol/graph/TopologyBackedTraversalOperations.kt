package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.evidence.sqlite.SqliteTopologyRelationCompiler
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.relation.service.RelationService
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotReader
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
                -> return rejected(TraversalRejection.OneHopRejected(
                    RelationReadRejection.WORKSPACE_NOT_READY,
                ))
        }
        if (plan.start.lease != workspace.readLease) {
            return rejected(TraversalRejection.OneHopRejected(
                RelationReadRejection.STALE_GENERATION,
            ))
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
        val relations = RelationService(
            workspaces,
            SqliteTopologyRelationCompiler(snapshot, contentReader),
        )
        return when (val result = traversalOperations(relations).run(plan)) {
            is TraversalResult.Rejected -> when (val reason = result.reason) {
                is TraversalRejection.OneHopRejected ->
                    if (reason.reason == RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE) {
                        rejected(TraversalRejection.RequiredEvidenceUnavailable)
                    } else {
                        result
                    }
                TraversalRejection.ReaderContractViolation,
                TraversalRejection.RequiredEvidenceStale,
                TraversalRejection.RequiredEvidenceUnavailable,
                TraversalRejection.TraversalContractViolation,
                    -> result
            }
            is TraversalResult.Complete,
            is TraversalResult.Qualified,
                -> result
        }
    }
}

private fun rejected(reason: TraversalRejection): TraversalResult =
    TraversalResult.Rejected(reason)
