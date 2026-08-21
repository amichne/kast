package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace

/** Closed persistence failures for one canonical workspace publication transaction. */
enum class WorkspacePublicationFailure {
    StorageUnavailable,
    CapabilityUnavailable,
}

/** Opaque ownership of one open canonical workspace publication transaction. */
interface OpenCanonicalWorkspacePublication

/** Opaque proof that one canonical workspace publication is complete and prepared. */
interface PreparedCanonicalWorkspacePublication

/** Closed result of opening the publication transaction before reconciliation starts. */
sealed interface WorkspacePublicationOpening {
    data class Opened(
        val publication: OpenCanonicalWorkspacePublication,
    ) : WorkspacePublicationOpening

    data class Rejected(
        val failure: WorkspacePublicationFailure,
    ) : WorkspacePublicationOpening
}

/** Closed result of preparing reconciled evidence for atomic commit. */
sealed interface WorkspacePublicationPreparation {
    data class Prepared(
        val publication: PreparedCanonicalWorkspacePublication,
    ) : WorkspacePublicationPreparation

    data class Rejected(
        val failure: WorkspacePublicationFailure,
    ) : WorkspacePublicationPreparation
}

/** Atomic result of publishing one reconciled candidate and its evidence generation. */
sealed interface WorkspacePublicationResult {
    data class Advanced(
        val workspace: PublishedWorkspace,
    ) : WorkspacePublicationResult

    data class Unchanged(
        val workspace: PublishedWorkspace,
    ) : WorkspacePublicationResult

    data class Rejected(
        val failure: WorkspacePublicationFailure,
    ) : WorkspacePublicationResult
}

/** Closed result of discarding an open or prepared publication transaction. */
sealed interface WorkspacePublicationDiscard {
    data object Discarded : WorkspacePublicationDiscard

    data class Rejected(
        val failure: WorkspacePublicationFailure,
    ) : WorkspacePublicationDiscard
}

/** Persistence boundary for one atomic canonical workspace publication transaction. */
interface WorkspacePublicationTransaction {
    /**
     * Proof transition: `WorkspacePublicationTransaction -> WorkspacePublicationOpening`.
     *
     * Establishes exclusive ownership of the transaction that will receive reconciliation
     * evidence, or the closed [WorkspacePublicationFailure].
     */
    fun begin(): WorkspacePublicationOpening

    /**
     * Proof transition: `(OpenCanonicalWorkspacePublication, ReconciledWorkspace) ->
     * WorkspacePublicationPreparation`.
     *
     * Establishes that complete candidate evidence and its generation manifest share the open
     * atomic transaction, or the closed [WorkspacePublicationFailure].
     */
    fun prepare(
        open: OpenCanonicalWorkspacePublication,
        candidate: ReconciledWorkspace,
    ): WorkspacePublicationPreparation

    /**
     * Proof transition: `PreparedCanonicalWorkspacePublication -> WorkspacePublicationResult`.
     *
     * An advanced result atomically commits one new evidence generation. An unchanged result
     * proves the candidate is canonically identical and retains the current generation. Both
     * return the only [PublishedWorkspace] assembled from that exact prepared candidate. The
     * closed expected failure is [WorkspacePublicationFailure]. Persistence primitives may be
     * extracted only by the evidence adapter implementing this transaction.
     */
    fun commit(prepared: PreparedCanonicalWorkspacePublication): WorkspacePublicationResult

    fun discard(open: OpenCanonicalWorkspacePublication): WorkspacePublicationDiscard

    fun discard(prepared: PreparedCanonicalWorkspacePublication): WorkspacePublicationDiscard
}
