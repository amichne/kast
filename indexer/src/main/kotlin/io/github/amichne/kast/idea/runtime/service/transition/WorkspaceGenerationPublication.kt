package io.github.amichne.kast.idea

import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.evidence.sqlite.IndexStoreWorkspaceGenerationPublication
import io.github.amichne.kast.evidence.sqlite.IndexStoreWorkspacePublicationCurrency
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Legacy composition bridge consumed only through evidence publication contracts. */
internal interface WorkspaceGenerationPublication {
    fun current(): PublishedWorkspaceGenerationState

    fun currency(manifest: PublishedWorkspaceGenerationManifest): IndexStoreWorkspacePublicationCurrency

    fun begin(): OpenWorkspacePublication

    fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication

    fun commit(prepared: PreparedWorkspacePublication): WorkspacePublicationCommit

    fun storedCommit(commit: WorkspacePublicationCommit): WorkspaceGenerationCommit

    fun discard(open: OpenWorkspacePublication)

    fun discard(prepared: PreparedWorkspacePublication)
}

internal class PersistentWorkspaceGenerationPublication(
    store: WorkspaceGenerationStore,
) : WorkspaceGenerationPublication {
    private val delegate = IndexStoreWorkspaceGenerationPublication(store)

    override fun current(): PublishedWorkspaceGenerationState = delegate.current()

    override fun currency(
        manifest: PublishedWorkspaceGenerationManifest,
    ): IndexStoreWorkspacePublicationCurrency = delegate.currency(manifest)

    override fun begin(): OpenWorkspacePublication = delegate.begin()

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication = delegate.prepare(open, identity, graphPublication)

    override fun commit(prepared: PreparedWorkspacePublication): WorkspacePublicationCommit =
        delegate.commit(prepared).commit

    override fun storedCommit(commit: WorkspacePublicationCommit): WorkspaceGenerationCommit =
        delegate.storedCommit(commit)

    override fun discard(prepared: PreparedWorkspacePublication) = delegate.discard(prepared)

    override fun discard(open: OpenWorkspacePublication) = delegate.discard(open)
}
