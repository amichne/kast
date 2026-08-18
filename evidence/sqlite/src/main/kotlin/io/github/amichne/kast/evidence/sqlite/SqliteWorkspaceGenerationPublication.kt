package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationAuthority
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/**
 * Direct SQLite authority for one begin, prepare, commit-or-discard generation publication.
 *
 * Opaque outward capabilities retain the exact database session and adapter owner. No JDBC handle
 * or weaker persistence authority crosses this boundary.
 */
class SqliteWorkspaceGenerationPublication private constructor(
    private val database: SqliteWorkspacePublicationDatabase,
    private val faultInjector: SqliteWorkspacePublicationFaultInjector,
) : WorkspacePublicationAuthority {
    constructor(database: SqliteWorkspacePublicationDatabase) : this(
        database,
        SqliteWorkspacePublicationFaultInjector.Disabled,
    )

    private val owner = Owner()

    override fun current(): PublishedWorkspaceGenerationState = database.current()?.let {
        PublishedWorkspaceGenerationState.Published(it.publication)
    } ?: PublishedWorkspaceGenerationState.Unpublished

    override fun begin(): OpenWorkspacePublication = OwnedOpen(
        session = database.begin(faultInjector),
        owner = owner,
    )

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication {
        val session = open.requireOwned()
        session.prepare(identity, graphPublication)
        return OwnedPrepared(session, owner)
    }

    override fun commit(
        prepared: PreparedWorkspacePublication,
    ): GenerationPublication.Published = GenerationPublication.Published(
        SqliteWorkspacePublicationCommit(prepared.requireOwned().commit().publication),
    )

    override fun discard(open: OpenWorkspacePublication) = open.requireOwned().discard()

    override fun discard(prepared: PreparedWorkspacePublication) = prepared.requireOwned().discard()

    /**
     * Proof transition: `OpenWorkspacePublication -> SqliteWorkspacePublicationSession`.
     *
     * Establishes that the opaque open capability belongs to this exact adapter instance. A
     * foreign capability is programmer misuse; raw JDBC state remains inside the session.
     */
    private fun OpenWorkspacePublication.requireOwned(): SqliteWorkspacePublicationSession =
        (this as? OwnedOpen)?.takeIf { it.owner === owner }?.session
        ?: error("Open workspace publication belongs to another SQLite authority")

    /**
     * Proof transition: `PreparedWorkspacePublication -> SqliteWorkspacePublicationSession`.
     *
     * Establishes that the opaque prepared capability belongs to this exact adapter instance. A
     * foreign capability is programmer misuse; raw JDBC state remains inside the session.
     */
    private fun PreparedWorkspacePublication.requireOwned(): SqliteWorkspacePublicationSession =
        (this as? OwnedPrepared)?.takeIf { it.owner === owner }?.session
        ?: error("Prepared workspace publication belongs to another SQLite authority")

    internal companion object {
        fun faultInjecting(
            database: SqliteWorkspacePublicationDatabase,
            faultInjector: SqliteWorkspacePublicationFaultInjector,
        ): SqliteWorkspaceGenerationPublication = SqliteWorkspaceGenerationPublication(
            database,
            faultInjector,
        )
    }

    private class Owner

    private data class OwnedOpen(
        val session: SqliteWorkspacePublicationSession,
        val owner: Owner,
    ) : OpenWorkspacePublication

    private data class OwnedPrepared(
        val session: SqliteWorkspacePublicationSession,
        val owner: Owner,
    ) : PreparedWorkspacePublication
}

private data class SqliteWorkspacePublicationCommit(
    override val publication: PublishedWorkspaceGeneration,
) : WorkspacePublicationCommit
