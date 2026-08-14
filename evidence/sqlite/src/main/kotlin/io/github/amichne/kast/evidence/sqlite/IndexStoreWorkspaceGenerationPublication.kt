package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.evidence.spi.WorkspacePublicationAuthority
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
 * Closed observation of an expected index-store publication manifest.
 *
 * [Current] preserves the exact durable manifest equality proof. [Moved] preserves both the
 * expected manifest and the strongest observed durable publication state.
 */
sealed interface IndexStoreWorkspacePublicationCurrency {
    data class Current(
        val manifest: PublishedWorkspaceGenerationManifest,
    ) : IndexStoreWorkspacePublicationCurrency

    data class Moved(
        val expected: PublishedWorkspaceGenerationManifest,
        val observed: StoredPublicationState,
    ) : IndexStoreWorkspacePublicationCurrency
}

/**
 * SQLite publication authority backed by the existing atomic source-index transaction.
 *
 * The adapter owns every conversion between host-neutral evidence capabilities and index-store
 * transaction capabilities. It adds no SQL or second publication store.
 */
class IndexStoreWorkspaceGenerationPublication(
    private val store: WorkspaceGenerationStore,
) : WorkspacePublicationAuthority {
    private val persistence = IndexStoreWorkspacePublicationPersistence(store)
    private val delegate = SqliteWorkspaceGenerationPublication(persistence)

    override fun current(): PublishedWorkspaceGenerationState = delegate.current()

    override fun begin(): OpenWorkspacePublication = delegate.begin()

    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication = delegate.prepare(open, identity, graphPublication)

    override fun commit(prepared: PreparedWorkspacePublication): GenerationPublication.Published =
        delegate.commit(prepared)

    override fun discard(open: OpenWorkspacePublication) = delegate.discard(open)

    override fun discard(prepared: PreparedWorkspacePublication) = delegate.discard(prepared)

    /**
     * Proof transition: `PublishedWorkspaceGenerationManifest ->
     * IndexStoreWorkspacePublicationCurrency`.
     *
     * A current result proves exact equality with the durable publication row. A moved result
     * retains both the expected manifest and strongest observed durable state.
     */
    fun currency(expected: PublishedWorkspaceGenerationManifest): IndexStoreWorkspacePublicationCurrency {
        val observed = store.current()
        return if (observed == StoredPublicationState.Published(expected)) {
            IndexStoreWorkspacePublicationCurrency.Current(expected)
        } else {
            IndexStoreWorkspacePublicationCurrency.Moved(expected, observed)
        }
    }

    /**
     * Proof transition: `WorkspacePublicationCommit -> WorkspaceGenerationCommit`.
     *
     * Establishes that the detached commit retains the store-owned atomic commit produced by this
     * authority. Cross-authority input is programmer misuse at runtime composition.
     */
    fun storedCommit(commit: WorkspacePublicationCommit): WorkspaceGenerationCommit =
        persistence.storedCommit(commit)
}

private class IndexStoreWorkspacePublicationPersistence(
    private val store: WorkspaceGenerationStore,
) : SqliteWorkspacePublicationPersistence {
    override fun current(): PublishedWorkspaceGenerationState = when (val current = store.current()) {
        StoredPublicationState.Unpublished -> PublishedWorkspaceGenerationState.Unpublished
        is StoredPublicationState.Published ->
            PublishedWorkspaceGenerationState.Published(current.manifest.detachedPublication())
    }

    override fun begin(): OpenSqliteWorkspacePublication = StoreOpen(store, store.begin())

    fun storedCommit(commit: WorkspacePublicationCommit): WorkspaceGenerationCommit =
        (commit as? StoreCommit)?.commit
            ?: error("Workspace publication commit belongs to another persistence authority")
}

private class StoreOpen(
    private val store: WorkspaceGenerationStore,
    private val generation: OpenWorkspaceGeneration,
) : OpenSqliteWorkspacePublication {
    override fun prepare(
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedSqliteWorkspacePublication = StorePrepared(
        store,
        store.prepare(
            candidate = generation,
            identity = PublishedWorkspaceIdentity(identity.value),
            graphBlocker = when (graphPublication) {
                WorkspaceGraphPublication.Ready -> null
                WorkspaceGraphPublication.IndexingBlocked -> GraphEvidenceBlocker.INDEXING_FAILED
            },
        ),
    )

    override fun discard() = store.discard(generation)
}

private class StorePrepared(
    private val store: WorkspaceGenerationStore,
    private val generation: PreparedWorkspaceGeneration,
) : PreparedSqliteWorkspacePublication {
    override fun commit(): WorkspacePublicationCommit = StoreCommit(store.commit(generation))

    override fun discard() = store.discard(generation)
}

private data class StoreCommit(
    val commit: WorkspaceGenerationCommit,
) : WorkspacePublicationCommit {
    override val publication: PublishedWorkspaceGeneration = commit.manifest.detachedPublication()
}

/**
 * Proof transition:
 * `PublishedWorkspaceGenerationManifest -> PublishedWorkspaceGeneration`.
 *
 * Preserves the store-proven positive generation and non-blank workspace identity as detached
 * workspace evidence. Kernel rejection fails the adapter rather than weakening publication proof.
 */
fun PublishedWorkspaceGenerationManifest.detachedPublication(): PublishedWorkspaceGeneration {
    val generation = when (val parsed = EvidenceGeneration.parse(generation.value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            error("Stored workspace publication has an unrepresentable generation: ${generation.value}")
    }
    return PublishedWorkspaceGeneration(generation, WorkspaceStateIdentity(identity.value))
}
