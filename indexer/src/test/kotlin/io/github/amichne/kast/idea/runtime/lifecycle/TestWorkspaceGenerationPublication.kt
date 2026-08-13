package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticReadAuthority
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.RepositoryOverlayPublication
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationCommit
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.api.contract.result.SemanticGraphResult
import io.github.amichne.kast.api.validation.ParsedSemanticGraphQuery
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistence
import io.github.amichne.kast.change.plan.service.PersistAddDeclarationPlanResult

internal class TestWorkspaceGenerationPublication(
    initial: PublishedWorkspaceGenerationManifest? = null,
    private val onCommit: (WorkspaceStateIdentity) -> Unit = {},
) : WorkspaceGenerationPublication {
    private var published: PublishedWorkspaceGenerationManifest? = initial

    @Synchronized
    override fun current(): PublishedWorkspaceGenerationState = published
                                                                    ?.detachedPublication()
                                                                    ?.let(PublishedWorkspaceGenerationState::Published)
                                                                ?: PublishedWorkspaceGenerationState.Unpublished

    @Synchronized
    override fun matches(manifest: PublishedWorkspaceGenerationManifest): Boolean =
        published == manifest

    @Synchronized
    override fun begin(): OpenWorkspacePublication =
        TestOpenWorkspacePublication(
            generation = published?.generation?.next() ?: WorkspaceSemanticGeneration(1),
        )

    @Synchronized
    override fun prepare(
        open: OpenWorkspacePublication,
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ): PreparedWorkspacePublication = TestPreparedWorkspacePublication(
        generation = open.testPublication().generation,
        identity = identity,
    )

    @Synchronized
    override fun commit(prepared: PreparedWorkspacePublication): WorkspacePublicationCommit {
        val candidate = prepared.testPublication()
        val identity = candidate.identity
        onCommit(identity)
        val generation = testPublishedWorkspaceGeneration(candidate.generation, identity)
        published = generation
        return TestWorkspacePublicationCommit(WorkspaceGenerationCommit(generation))
    }

    override fun storedCommit(commit: WorkspacePublicationCommit): WorkspaceGenerationCommit =
        (commit as? TestWorkspacePublicationCommit)?.commit
        ?: error("Workspace publication commit belongs to another test authority")

    override fun discard(open: OpenWorkspacePublication) = Unit

    override fun discard(prepared: PreparedWorkspacePublication) = Unit
}

internal class TestWorkspaceSemanticReadAuthority(
    private val onReadOpened: () -> Unit = {},
    private val onReadClosed: () -> Unit = {},
    private val currentStatus: () -> IdeaIndexSemanticAdmission.Status = {
        IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration())
    },
) : WorkspaceSemanticReadAuthority {
    override fun status(): IdeaIndexSemanticAdmission.Status = currentStatus()

    override fun openRead(): IdeaIndexSemanticAdmission.WorkspaceReadToken {
        val ready = currentStatus() as? IdeaIndexSemanticAdmission.Status.Ready
                    ?: error("Workspace semantic generation is not READY")
        onReadOpened()
        return IdeaIndexSemanticAdmission.WorkspaceReadToken(
            revision = TEST_REVISION,
            generation = ready.generation,
            release = onReadClosed,
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
    suspend (io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest) ->
    PublishedWorkspaceGenerationManifest =
        { published },
) : WorkspaceTransitionPort {
    override suspend fun reconcile(
        request: io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest,
    ): WorkspaceTransitionOutcome =
        WorkspaceTransitionOutcome.Published(onReconcile(request).detachedPublication())

    override suspend fun <T> mutate(
        signal: io.github.amichne.kast.workspace.contract.WorkspaceSignal,
        detail: String,
        operation: suspend () -> T,
    ): WorkspaceMutationTransitionOutcome<T> {
        val value = operation()
        return WorkspaceMutationTransitionOutcome.Completed(value, published.detachedPublication())
    }
}

internal suspend fun KastIndexerBackend.reconcileSemanticGraphForTest(
    query: ParsedSemanticGraphQuery,
): SemanticGraphResult = reconcileSemanticGraph(
    query,
    IdeaIndexSemanticAdmission.ReconciliationToken(TEST_RECONCILIATION_REVISION),
)

private const val TEST_RECONCILIATION_REVISION = 1L

private class TestOpenWorkspacePublication(
    val generation: WorkspaceSemanticGeneration,
) : OpenWorkspacePublication

private class TestPreparedWorkspacePublication(
    val generation: WorkspaceSemanticGeneration,
    val identity: WorkspaceStateIdentity,
) : PreparedWorkspacePublication

private data class TestWorkspacePublicationCommit(
    val commit: WorkspaceGenerationCommit,
) : WorkspacePublicationCommit {
    override val publication: PublishedWorkspaceGeneration = commit.manifest.detachedPublication()
}

private fun OpenWorkspacePublication.testPublication(): TestOpenWorkspacePublication =
    this as? TestOpenWorkspacePublication
    ?: error("Open workspace generation belongs to another test authority")

private fun PreparedWorkspacePublication.testPublication(): TestPreparedWorkspacePublication =
    this as? TestPreparedWorkspacePublication
    ?: error("Prepared workspace generation belongs to another test authority")

internal fun testPublishedWorkspaceGeneration(
    generation: WorkspaceSemanticGeneration = WorkspaceSemanticGeneration(1),
    identity: WorkspaceStateIdentity = WorkspaceStateIdentity("test-workspace-state"),
): PublishedWorkspaceGenerationManifest = PublishedWorkspaceGenerationManifest(
    generation = generation,
    identity = PublishedWorkspaceIdentity(identity.value),
    sourceIndexGeneration = SourceIndexGeneration(generation.value),
    sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
    publishedAt = PublicationEpochMillis.fromClock(1),
    repositoryOverlay = RepositoryOverlayPublication.ABSENT,
)

internal data object TestAddDeclarationPlanPersistence : AddDeclarationPlanPersistence {
    override fun persist(plan: PlannedAddDeclaration): PersistAddDeclarationPlanResult =
        PersistAddDeclarationPlanResult.Stored(
            PersistedAddDeclarationPlan.awaitingApproval(plan),
        )
}
