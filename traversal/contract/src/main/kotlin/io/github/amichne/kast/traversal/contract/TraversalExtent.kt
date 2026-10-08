package io.github.amichne.kast.traversal.contract

/** Semantic reach is independent of the grants used to execute and retain it. */
sealed interface TraversalExtent {
    data object Exhaustive : TraversalExtent

    data class ThroughDepth(val maximumDepth: TraversalDepthLimit) : TraversalExtent

    fun defaultExpansion(): io.github.amichne.kast.relation.contract.RelationSearchBoundary =
        when (this) {
            Exhaustive -> io.github.amichne.kast.relation.contract.RelationSearchBoundary.WORKSPACE_EXPANSION
            is ThroughDepth -> io.github.amichne.kast.relation.contract.RelationSearchBoundary.RETAINED_SUBJECT
        }

    fun permitsExpansion(depth: TraversalDepth): Boolean =
        when (this) {
            Exhaustive -> true
            is ThroughDepth -> depth.value < maximumDepth.value
        }

    fun permitsRecord(depth: TraversalDepth): Boolean =
        when (this) {
            Exhaustive -> true
            is ThroughDepth -> depth.value <= maximumDepth.value
        }

    fun exceeds(ceiling: TraversalDepthLimit): Boolean =
        when (this) {
            Exhaustive -> false
            is ThroughDepth -> maximumDepth.value > ceiling.value
        }
}
