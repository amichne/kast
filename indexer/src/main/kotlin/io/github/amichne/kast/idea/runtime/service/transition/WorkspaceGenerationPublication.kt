package io.github.amichne.kast.idea

import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationAuthority
import io.github.amichne.kast.evidence.sqlite.SqliteWorkspaceGenerationPublication
import io.github.amichne.kast.evidence.sqlite.SqliteWorkspacePublicationDatabase
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Runtime-facing workspace publication authority with a commit that is always physically final. */
internal interface WorkspaceGenerationPublication : WorkspacePublicationAuthority {
    override fun commit(prepared: PreparedWorkspacePublication): GenerationPublication.Published
}

/** Direct SQLite implementation composed from one already-admitted publication database. */
internal class PersistentWorkspaceGenerationPublication(
    database: SqliteWorkspacePublicationDatabase,
) : WorkspaceGenerationPublication {
    private val delegate = SqliteWorkspaceGenerationPublication(database)

    override fun current(): PublishedWorkspaceGenerationState = delegate.current()

    override fun begin(): OpenWorkspacePublication = delegate.begin()

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication = delegate.prepare(open, identity, graphPublication)

    override fun commit(
        prepared: PreparedWorkspacePublication,
    ): GenerationPublication.Published = delegate.commit(prepared)

    override fun discard(prepared: PreparedWorkspacePublication) = delegate.discard(prepared)

    override fun discard(open: OpenWorkspacePublication) = delegate.discard(open)
}
