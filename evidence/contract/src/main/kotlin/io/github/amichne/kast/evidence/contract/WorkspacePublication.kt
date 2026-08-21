package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration

/**
 * Opaque ownership of an active workspace publication before completeness validation.
 */
interface OpenWorkspacePublication

/**
 * Opaque proof that an active workspace publication passed completeness and identity validation.
 */
interface PreparedWorkspacePublication

enum class WorkspaceGraphPublication {
    Ready,
    IndexingBlocked,
}

/**
 * Proof that one prepared persistent publication committed atomically.
 */
interface WorkspacePublicationCommit {
    val publication: PublishedWorkspaceGeneration
}

sealed interface GenerationPublication {
    sealed interface Committed : GenerationPublication {
        val commit: WorkspacePublicationCommit
    }

    data class Published(
        override val commit: WorkspacePublicationCommit,
    ) : Committed

    data class Unchanged(
        override val commit: WorkspacePublicationCommit,
    ) : Committed

    data object InvalidatedBeforeCommit : GenerationPublication

    data class InvalidatedAfterCommit(
        val commit: WorkspacePublicationCommit,
    ) : GenerationPublication
}
