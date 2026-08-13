package io.github.amichne.kast.idea

import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.indexstore.snapshot.GraphEvidenceBlocker
import io.github.amichne.kast.indexstore.snapshot.OpenWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PreparedWorkspaceGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState as StoredPublicationState
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/**
 * Legacy persistence adapter consumed only through evidence publication contracts.
 */
internal interface WorkspaceGenerationPublication {
    fun current(): PublishedWorkspaceGenerationState

    fun matches(manifest: PublishedWorkspaceGenerationManifest): Boolean

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
    private val store: WorkspaceGenerationStore,
) : WorkspaceGenerationPublication {
    override fun current(): PublishedWorkspaceGenerationState = when (val current = store.current()) {
        StoredPublicationState.Unpublished -> PublishedWorkspaceGenerationState.Unpublished
        is StoredPublicationState.Published ->
            PublishedWorkspaceGenerationState.Published(current.manifest.detachedPublication())
    }

    override fun matches(manifest: PublishedWorkspaceGenerationManifest): Boolean =
        store.current() == StoredPublicationState.Published(manifest)

    override fun begin(): OpenWorkspacePublication = StoreOpenWorkspacePublication(store.begin())

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication = StorePreparedWorkspacePublication(
        store.prepare(
            candidate = open.storeGeneration(),
            identity = PublishedWorkspaceIdentity(identity.value),
            graphBlocker = when (graphPublication) {
                WorkspaceGraphPublication.Ready -> null
                WorkspaceGraphPublication.IndexingBlocked -> GraphEvidenceBlocker.INDEXING_FAILED
            },
        ),
    )

    override fun commit(prepared: PreparedWorkspacePublication): WorkspacePublicationCommit =
        store.commit(prepared.storeGeneration()).let(::StoreWorkspacePublicationCommit)

    override fun storedCommit(commit: WorkspacePublicationCommit): WorkspaceGenerationCommit =
        (commit as? StoreWorkspacePublicationCommit)?.commit
        ?: error("Workspace publication commit belongs to another persistence authority")

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

    private data class StoreWorkspacePublicationCommit(
        val commit: WorkspaceGenerationCommit,
    ) : WorkspacePublicationCommit {
        override val publication: PublishedWorkspaceGeneration =
            commit.manifest.detachedPublication()
    }
}

/**
 * Proof transition:
 * `PublishedWorkspaceGenerationManifest -> PublishedWorkspaceGeneration`.
 *
 * Preserves the store-proven positive generation and non-blank workspace identity as detached
 * workspace evidence. Kernel rejection is closed and fails the persistence adapter rather than
 * weakening publication proof. Raw store values may be extracted only at this adapter boundary.
 */
internal fun PublishedWorkspaceGenerationManifest.detachedPublication(): PublishedWorkspaceGeneration {
    val detachedGeneration = when (val parsed = EvidenceGeneration.parse(generation.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            error("Stored workspace publication has an unrepresentable generation: ${generation.value}")
    }
    return PublishedWorkspaceGeneration(
        generation = detachedGeneration,
        identity = WorkspaceStateIdentity(identity.value),
    )
}
