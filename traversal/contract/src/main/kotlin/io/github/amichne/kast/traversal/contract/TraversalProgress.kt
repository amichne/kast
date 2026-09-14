package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement

/** Detached cumulative work evidence; counters describe committed pages, never elapsed wall time. */
@ConsistentCopyVisibility
data class TraversalProgress
private constructor(
    val checkpointSequence: Long,
    val totalReads: Long,
    val totalEdges: Long,
    val maximumDepthReached: Int,
) {
    fun advance(reads: Int, edges: Int, depth: Int): Refinement<TraversalProgress, TraversalProgressFailure> {
        if (reads < 0 || edges < 0 || depth < 0) return Refinement.Rejected(TraversalProgressFailure.NEGATIVE)
        if (reads == 0)
            return if (edges == 0) Refinement.Refined(this)
            else Refinement.Rejected(TraversalProgressFailure.EDGES_WITHOUT_READ)
        if (
            checkpointSequence == Long.MAX_VALUE ||
                totalReads > Long.MAX_VALUE - reads ||
                totalEdges > Long.MAX_VALUE - edges
        )
            return Refinement.Rejected(TraversalProgressFailure.OVERFLOW)
        return restore(
            checkpointSequence + 1L,
            totalReads + reads,
            totalEdges + edges,
            maxOf(maximumDepthReached, depth),
        )
    }

    companion object {
        val Initial = TraversalProgress(0L, 0L, 0L, 0)

        fun restore(
            sequence: Long,
            reads: Long,
            edges: Long,
            depth: Int,
        ): Refinement<TraversalProgress, TraversalProgressFailure> =
            when {
                sequence < 0L || reads < 0L || edges < 0L || depth < 0 ->
                    Refinement.Rejected(TraversalProgressFailure.NEGATIVE)
                sequence > reads || (reads == 0L && (edges > 0L || depth > 0)) ->
                    Refinement.Rejected(TraversalProgressFailure.EDGES_WITHOUT_READ)
                else -> Refinement.Refined(TraversalProgress(sequence, reads, edges, depth))
            }
    }
}

enum class TraversalProgressFailure {
    NEGATIVE,
    EDGES_WITHOUT_READ,
    OVERFLOW,
}
