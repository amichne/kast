package io.github.amichne.kast.change.verify

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease

/** Finite effect failures before a candidate resulting publication exists. */
enum class ResultingGenerationPublicationRejection {
    CURRENT_PUBLICATION_UNAVAILABLE,
    RECONCILIATION_INVALIDATED,
    RECONCILIATION_BLOCKED,
    PUBLICATION_PROTOCOL_REJECTED,
}

/** Closed result of requesting one publication after an applied source mutation. */
sealed interface ResultingGenerationPublication {
    data class Published(
        val workspace: PublishedWorkspace,
    ) : ResultingGenerationPublication

    data class Rejected(
        val reason: ResultingGenerationPublicationRejection,
    ) : ResultingGenerationPublication
}

/** Effect port for publication of the semantic generation resulting from G0 application. */
fun interface ResultingGenerationPublisher {
    /**
     * Proof transition: `SemanticReadLease -> ResultingGenerationPublication`.
     *
     * A published candidate carries complete KCS-007 workspace coverage but remains weaker than
     * [DistinctResultingWorkspace] until root and monotonic generation are admitted. Expected
     * effect failure is closed by [ResultingGenerationPublicationRejection]. Raw workspace effects
     * remain inside the publisher implementation.
     */
    fun publishAfter(prior: SemanticReadLease): ResultingGenerationPublication
}

enum class DistinctResultingWorkspaceFailure {
    WORKSPACE_ROOT_CHANGED,
    GENERATION_NOT_NEWER,
}

/** Exact complete workspace publication proven distinct from the applied mutation's G0 lease. */
class DistinctResultingWorkspace private constructor(
    val prior: SemanticReadLease,
    val workspace: PublishedWorkspace,
) {
    companion object {
        /**
         * Proof transition: `(SemanticReadLease, PublishedWorkspace) -> Refinement<
         * DistinctResultingWorkspace, DistinctResultingWorkspaceFailure>`.
         *
         * Establishes the exact canonical root and a strictly newer complete published generation.
         * [DistinctResultingWorkspaceFailure] is the closed expected failure. Raw generation
         * comparison is permitted only at this workspace-publication admission boundary.
         */
        fun admit(
            prior: SemanticReadLease,
            published: PublishedWorkspace,
        ): Refinement<DistinctResultingWorkspace, DistinctResultingWorkspaceFailure> = when {
            published.root != prior.workspaceRoot ->
                Refinement.Rejected(DistinctResultingWorkspaceFailure.WORKSPACE_ROOT_CHANGED)
            published.generation.value <= prior.generation.value ->
                Refinement.Rejected(DistinctResultingWorkspaceFailure.GENERATION_NOT_NEWER)
            else -> Refinement.Refined(DistinctResultingWorkspace(prior, published))
        }
    }
}
