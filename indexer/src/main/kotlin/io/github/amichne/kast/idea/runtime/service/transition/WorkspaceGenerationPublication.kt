package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.transition.OpenWorkspacePublication
import io.github.amichne.kast.idea.transition.PreparedWorkspacePublication
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.OpenWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PreparedWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore

internal interface WorkspaceGenerationPublication {
    fun current(): PublishedWorkspaceGenerationState

    fun begin(): OpenWorkspacePublication

    /**
     * Proof transition:
     * `(OpenWorkspacePublication, WorkspaceStateIdentity) -> PreparedWorkspacePublication`.
     *
     * Derives the only capability accepted by [commit] after completeness and
     * identity binding succeed. Raw SQLite state remains inside the persistent
     * publication adapter.
     */
    fun prepare(open: OpenWorkspacePublication, identity: WorkspaceStateIdentity): PreparedWorkspacePublication

    fun commit(prepared: PreparedWorkspacePublication): WorkspaceGenerationCommit

    fun discard(open: OpenWorkspacePublication)

    fun discard(prepared: PreparedWorkspacePublication)
}

internal class PersistentWorkspaceGenerationPublication(
    private val store: WorkspaceGenerationStore,
) : WorkspaceGenerationPublication {
    override fun current(): PublishedWorkspaceGenerationState = store.current()

    override fun begin(): OpenWorkspacePublication = StoreOpenWorkspacePublication(store.begin())

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
    ): PreparedWorkspacePublication = StorePreparedWorkspacePublication(
        store.prepare(open.storeGeneration(), PublishedWorkspaceIdentity(identity.value)),
    )

    override fun commit(prepared: PreparedWorkspacePublication): WorkspaceGenerationCommit =
        store.commit(prepared.storeGeneration())

    override fun discard(prepared: PreparedWorkspacePublication) {
        store.discard(prepared.storeGeneration())
    }

    override fun discard(open: OpenWorkspacePublication) {
        store.discard(open.storeGeneration())
    }

    private fun OpenWorkspacePublication.storeGeneration(): OpenWorkspaceGeneration =
        (this as? StoreOpenWorkspacePublication)?.generation
            ?: error("Open workspace generation belongs to another publication authority")

    private fun PreparedWorkspacePublication.storeGeneration(): PreparedWorkspaceGeneration =
        (this as? StorePreparedWorkspacePublication)?.generation
            ?: error("Prepared workspace generation belongs to another publication authority")

    private data class StorePreparedWorkspacePublication(
        val generation: PreparedWorkspaceGeneration,
    ) : PreparedWorkspacePublication

    private data class StoreOpenWorkspacePublication(
        val generation: OpenWorkspaceGeneration,
    ) : OpenWorkspacePublication
}
