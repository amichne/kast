package io.github.amichne.kast.query.contract

import io.github.amichne.kast.traversal.contract.TraversalExtent
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalProgress
import io.github.amichne.kast.traversal.contract.TraversalStrategy

/** Later exhaustion discharges a resumable page only for the same traversal and keeps every page's evidence. */
fun List<QueryWalkObservation>.hasUnresolvedWalkEvidence(): Boolean {
    val completions = mutableMapOf<WalkCompletionScope, MutableSet<TraversalProgress>>()
    return asReversed().any { observation ->
        val scope = WalkCompletionScope(observation.question, observation.budget.extent, observation.strategy)
        when (val coverage = observation.coverage) {
            QueryWalkCoverage.Complete -> {
                completions.getOrPut(scope, ::mutableSetOf).add(observation.progress)
                false
            }
            is QueryWalkCoverage.TerminalIncomplete -> true
            is QueryWalkCoverage.Resumable ->
                TraversalLimitation.ONE_HOP_INCOMPLETE in coverage.limitations &&
                    completions[scope].orEmpty().none { it.advances(observation.progress) }
        }
    }
}

private data class WalkCompletionScope(
    val question: QueryRelationQuestion,
    val extent: TraversalExtent,
    val strategy: TraversalStrategy,
)

private fun TraversalProgress.advances(prior: TraversalProgress): Boolean =
    checkpointSequence > prior.checkpointSequence &&
        totalReads > prior.totalReads &&
        totalEdges >= prior.totalEdges &&
        maximumDepthReached >= prior.maximumDepthReached
