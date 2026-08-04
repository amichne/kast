package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.transition.PreparedWorkspacePublication
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.PreparedWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore

internal interface WorkspaceGenerationPublication {
    fun current(): PublishedWorkspaceGenerationManifest?

    fun prepare(identity: WorkspaceStateIdentity): PreparedWorkspacePublication

    fun commit(prepared: PreparedWorkspacePublication): WorkspaceGenerationCommit

    fun discard(prepared: PreparedWorkspacePublication)
}

internal class PersistentWorkspaceGenerationPublication(
    private val store: WorkspaceGenerationStore,
) : WorkspaceGenerationPublication {
    override fun current(): PublishedWorkspaceGenerationManifest? = store.current()

    override fun prepare(identity: WorkspaceStateIdentity): PreparedWorkspacePublication =
        StorePreparedWorkspacePublication(
            store.prepare(PublishedWorkspaceIdentity(identity.value)),
        )

    override fun commit(prepared: PreparedWorkspacePublication): WorkspaceGenerationCommit =
        store.commit(prepared.storeGeneration())

    override fun discard(prepared: PreparedWorkspacePublication) {
        store.discard(prepared.storeGeneration())
    }

    private fun PreparedWorkspacePublication.storeGeneration(): PreparedWorkspaceGeneration =
        (this as? StorePreparedWorkspacePublication)?.generation
            ?: error("Prepared workspace generation belongs to another publication authority")

    private data class StorePreparedWorkspacePublication(
        val generation: PreparedWorkspaceGeneration,
    ) : PreparedWorkspacePublication
}
