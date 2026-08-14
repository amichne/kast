package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.evidence.spi.WorkspacePublicationAuthority
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/**
 * Narrow backing boundary for the existing source-index SQLite publication transaction.
 *
 * Implementations retain every store-specific type and transaction handle. This boundary exists
 * so the active SQLite adapter can own capability sequencing without depending on a legacy host.
 */
internal interface SqliteWorkspacePublicationPersistence {
    fun current(): PublishedWorkspaceGenerationState

    fun begin(): OpenSqliteWorkspacePublication
}

/**
 * Open SQLite publication capability produced by one persistence authority.
 *
 * Proof state: the backing transaction has begun and may be prepared or discarded exactly through
 * this capability. Store-specific transaction handles remain behind the capability boundary.
 */
internal interface OpenSqliteWorkspacePublication {
    fun prepare(
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedSqliteWorkspacePublication

    fun discard()
}

/**
 * Prepared SQLite publication capability produced from one open capability.
 *
 * Proof state: source-index facts and the publication manifest share one prepared transaction and
 * may be committed or discarded exactly through this capability.
 */
internal interface PreparedSqliteWorkspacePublication {
    fun commit(): WorkspacePublicationCommit

    fun discard()
}

/**
 * Owns the one begin, prepare, commit-or-discard protocol over SQLite publication persistence.
 *
 * The adapter introduces no SQL or second persistence authority. Its opaque wrappers prevent
 * capabilities from another adapter instance crossing into the backing transaction.
 */
internal class SqliteWorkspaceGenerationPublication(
    private val persistence: SqliteWorkspacePublicationPersistence,
) : WorkspacePublicationAuthority {
    private val owner = Owner()

    override fun current(): PublishedWorkspaceGenerationState = persistence.current()

    override fun begin(): OpenWorkspacePublication = OwnedOpen(persistence.begin(), owner)

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication = OwnedPrepared(
        open.requireOwned().prepare(identity, graphPublication),
        owner,
    )

    override fun commit(prepared: PreparedWorkspacePublication): GenerationPublication.Published =
        GenerationPublication.Published(prepared.requireOwned().commit())

    override fun discard(open: OpenWorkspacePublication) {
        open.requireOwned().discard()
    }

    override fun discard(prepared: PreparedWorkspacePublication) {
        prepared.requireOwned().discard()
    }

    /**
     * Proof transition: `OpenWorkspacePublication -> OpenSqliteWorkspacePublication`.
     *
     * Establishes that the outward capability was issued by this adapter instance. A capability
     * from another authority is programmer misuse; raw backing access is confined to this adapter.
     */
    private fun OpenWorkspacePublication.requireOwned(): OpenSqliteWorkspacePublication =
        (this as? OwnedOpen)?.takeIf { it.owner === owner }?.delegate
            ?: error("Open workspace publication belongs to another SQLite adapter")

    /**
     * Proof transition: `PreparedWorkspacePublication -> PreparedSqliteWorkspacePublication`.
     *
     * Establishes that the outward capability was issued by this adapter instance. A capability
     * from another authority is programmer misuse; raw backing access is confined to this adapter.
     */
    private fun PreparedWorkspacePublication.requireOwned(): PreparedSqliteWorkspacePublication =
        (this as? OwnedPrepared)?.takeIf { it.owner === owner }?.delegate
            ?: error("Prepared workspace publication belongs to another SQLite adapter")

    private class Owner

    private data class OwnedOpen(
        val delegate: OpenSqliteWorkspacePublication,
        val owner: Owner,
    ) : OpenWorkspacePublication

    private data class OwnedPrepared(
        val delegate: PreparedSqliteWorkspacePublication,
        val owner: Owner,
    ) : PreparedWorkspacePublication
}
