package io.github.amichne.kast.workspace.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspacePublicationBlocker

enum class ResultingWorkspacePublicationAdmissionFailure {
    WORKSPACE_ROOT_CHANGED,
    GENERATION_NOT_NEWER,
}

/** Complete workspace publication proven to result from one exact prior semantic lease. */
class ResultingWorkspacePublication private constructor(
    val prior: SemanticReadLease,
    val workspace: PublishedWorkspace,
) {
    companion object {
        /**
         * Proof transition: `(SemanticReadLease, PublishedWorkspace) -> Refinement<
         * ResultingWorkspacePublication, ResultingWorkspacePublicationAdmissionFailure>`.
         *
         * Establishes the same canonical root and a strictly newer complete workspace generation.
         * [ResultingWorkspacePublicationAdmissionFailure] is the closed expected failure. Raw
         * generation comparison is permitted only at this publication boundary.
         */
        fun admit(
            prior: SemanticReadLease,
            workspace: PublishedWorkspace,
        ): Refinement<ResultingWorkspacePublication, ResultingWorkspacePublicationAdmissionFailure> =
            when {
                workspace.root != prior.workspaceRoot -> Refinement.Rejected(
                    ResultingWorkspacePublicationAdmissionFailure.WORKSPACE_ROOT_CHANGED,
                )
                workspace.generation.value <= prior.generation.value -> Refinement.Rejected(
                    ResultingWorkspacePublicationAdmissionFailure.GENERATION_NOT_NEWER,
                )
                else -> Refinement.Refined(ResultingWorkspacePublication(prior, workspace))
            }
    }
}

sealed interface ResultingWorkspacePublicationFailure {
    data object CurrentPublicationUnavailable : ResultingWorkspacePublicationFailure

    data class PriorPublicationMismatch(
        val expected: SemanticReadLease,
        val current: SemanticReadLease,
    ) : ResultingWorkspacePublicationFailure

    data object NoPublication : ResultingWorkspacePublicationFailure

    data object Invalidated : ResultingWorkspacePublicationFailure

    data class Blocked(
        val blocker: WorkspacePublicationBlocker,
    ) : ResultingWorkspacePublicationFailure

    data class InvalidResult(
        val failure: ResultingWorkspacePublicationAdmissionFailure,
    ) : ResultingWorkspacePublicationFailure
}

sealed interface ResultingWorkspacePublicationResult {
    data class Published(
        val publication: ResultingWorkspacePublication,
    ) : ResultingWorkspacePublicationResult

    data class Rejected(
        val failure: ResultingWorkspacePublicationFailure,
    ) : ResultingWorkspacePublicationResult
}
