package io.github.amichne.kast.evidence.contract

import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/**
 * Single authority for one begin, prepare, commit-or-discard workspace publication protocol.
 *
 * Implementations may retain private persistence and admission handles, but only detached
 * publication evidence and opaque transaction capabilities cross this boundary.
 */
interface WorkspacePublicationAuthority {
    fun current(): PublishedWorkspaceGenerationState

    fun begin(): OpenWorkspacePublication

    /**
     * Proof transition:
     * `(OpenWorkspacePublication, WorkspaceStateIdentity, WorkspaceGraphPublication) ->
     * PreparedWorkspacePublication`.
     *
     * Establishes publication completeness and binds the reconciled identity without committing.
     * Persistence failures are unexpected adapter failures; raw store handles remain inside the
     * implementation.
     */
    fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication

    fun commit(prepared: PreparedWorkspacePublication): GenerationPublication

    fun discard(open: OpenWorkspacePublication)

    fun discard(prepared: PreparedWorkspacePublication)
}
