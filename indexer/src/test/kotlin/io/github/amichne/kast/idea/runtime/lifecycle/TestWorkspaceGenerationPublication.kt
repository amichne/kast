package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticReadAuthority
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.transition.PreparedWorkspacePublication
import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery

internal class TestWorkspaceGenerationPublication(
    initial: PublishedWorkspaceGenerationManifest? = null,
    private val onCommit: (WorkspaceStateIdentity) -> Unit = {},
) : WorkspaceGenerationPublication {
    private var published = initial

    @Synchronized
    override fun current(): PublishedWorkspaceGenerationManifest? = published

    @Synchronized
    override fun prepare(identity: WorkspaceStateIdentity): PreparedWorkspacePublication =
        TestPreparedWorkspacePublication(
            identity = identity,
            generation = published?.generation?.next() ?: WorkspaceSemanticGeneration(1),
        )

    @Synchronized
    override fun commit(prepared: PreparedWorkspacePublication): WorkspaceGenerationCommit {
        val candidate = prepared as? TestPreparedWorkspacePublication
            ?: error("Prepared workspace generation belongs to another test authority")
        onCommit(candidate.identity)
        val generation = testPublishedWorkspaceGeneration(candidate.generation, candidate.identity)
        published = generation
        return WorkspaceGenerationCommit.Durable(generation)
    }

    override fun discard(prepared: PreparedWorkspacePublication) = Unit
}

internal class TestWorkspaceSemanticReadAuthority(
    private val currentStatus: () -> IdeaIndexSemanticAdmission.Status = {
        IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration())
    },
) : WorkspaceSemanticReadAuthority {
    override fun status(): IdeaIndexSemanticAdmission.Status = currentStatus()

    override fun openRead(): IdeaIndexSemanticAdmission.WorkspaceReadToken {
        val ready = currentStatus() as? IdeaIndexSemanticAdmission.Status.Ready
            ?: error("Workspace semantic generation is not READY")
        return IdeaIndexSemanticAdmission.WorkspaceReadToken(
            revision = TEST_REVISION,
            generation = ready.generation,
            release = {},
        )
    }

    override fun isReadCurrent(token: IdeaIndexSemanticAdmission.WorkspaceReadToken): Boolean =
        (currentStatus() as? IdeaIndexSemanticAdmission.Status.Ready)?.generation == token.generation

    override fun isReconciliationCurrent(token: IdeaIndexSemanticAdmission.ReconciliationToken): Boolean = true

    private companion object {
        const val TEST_REVISION = 1L
    }
}

internal class TestWorkspaceTransitionRequester(
    private val published: PublishedWorkspaceGenerationManifest = testPublishedWorkspaceGeneration(),
    private val onReconcile:
        suspend (io.github.amichne.kast.idea.transition.WorkspaceSignal) -> PublishedWorkspaceGenerationManifest =
        { published },
) : WorkspaceTransitionRequester {
    override suspend fun reconcile(signal: io.github.amichne.kast.idea.transition.WorkspaceSignal):
        PublishedWorkspaceGenerationManifest = onReconcile(signal)

    override suspend fun <T> mutate(
        signal: io.github.amichne.kast.idea.transition.WorkspaceSignal,
        detail: String,
        operation: suspend () -> T,
    ): T = operation()
}

internal suspend fun KastIndexerBackend.reconcileSemanticGraphForTest(
    query: ParsedSemanticGraphQuery,
): SemanticGraphResult = reconcileSemanticGraph(
    query,
    IdeaIndexSemanticAdmission.ReconciliationToken(TEST_RECONCILIATION_REVISION),
)

private const val TEST_RECONCILIATION_REVISION = 1L

private data class TestPreparedWorkspacePublication(
    val identity: WorkspaceStateIdentity,
    val generation: WorkspaceSemanticGeneration,
) : PreparedWorkspacePublication

internal fun testPublishedWorkspaceGeneration(
    generation: WorkspaceSemanticGeneration = WorkspaceSemanticGeneration(1),
    identity: WorkspaceStateIdentity = WorkspaceStateIdentity("test-workspace-state"),
): PublishedWorkspaceGenerationManifest = PublishedWorkspaceGenerationManifest(
    generation = generation,
    identity = PublishedWorkspaceIdentity(identity.value),
    sourceIndexGeneration = SourceIndexGeneration(generation.value),
    sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
    databaseFile = "generation-${generation.value}/source-index.db",
    publishedAtEpochMillis = 1,
)
