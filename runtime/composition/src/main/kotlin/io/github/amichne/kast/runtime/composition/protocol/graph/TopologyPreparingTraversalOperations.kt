package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult

/**
 * Composition-owned traversal prerequisite.
 *
 * Topology remains an explicit internal capability, but callers no longer schedule it. A traversal
 * can proceed only after [TopologyBuildOperations] proves an exact eligible snapshot for the current
 * workspace generation. [TopologyBuildResult.WorkspaceMoved] is distinct from an unavailable
 * build: it preserves the stronger fact that the request's generation moved while preparing.
 */
internal class TopologyPreparingTraversalOperations(
    private val topology: TopologyBuildOperations,
    private val traversal: TraversalOperations,
) : TraversalOperations {
    override suspend fun run(plan: TraversalPlan): TraversalResult =
        when (val preparation = topology.build()) {
            is TopologyBuildResult.Published,
            is TopologyBuildResult.Reused,
                -> traversal.run(plan)

            TopologyBuildResult.WorkspaceMoved -> TraversalResult.Rejected(
                TraversalRejection.RequiredEvidenceStale,
            )

            is TopologyBuildResult.Rejected -> TraversalResult.Rejected(
                TraversalRejection.RequiredEvidenceUnavailable,
            )
        }
}
