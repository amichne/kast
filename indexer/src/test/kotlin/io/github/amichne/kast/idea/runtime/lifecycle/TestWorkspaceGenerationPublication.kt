package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.semantic.WorkspaceSemanticReadAuthority
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.GenerationPublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationCommit
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
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
    initial: PublishedWorkspaceGeneration? = null,
    private val onCommit: (WorkspaceStateIdentity) -> Unit = {},
) : WorkspaceGenerationPublication {
    private var published: PublishedWorkspaceGeneration? = initial

    @Synchronized
    override fun current(): PublishedWorkspaceGenerationState = published
        ?.let(PublishedWorkspaceGenerationState::Published)
        ?: PublishedWorkspaceGenerationState.Unpublished

    @Synchronized
    override fun begin(): OpenWorkspacePublication =
        TestOpenWorkspacePublication(
            generation = evidenceGeneration((published?.generation?.value ?: 0L) + 1L),
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
    override fun commit(prepared: PreparedWorkspacePublication): GenerationPublication.Published {
        val candidate = prepared.testPublication()
        val identity = candidate.identity
        onCommit(identity)
        val generation = testPublishedWorkspaceGeneration(candidate.generation, identity)
        published = generation
        return GenerationPublication.Published(TestWorkspacePublicationCommit(generation))
    }

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
    private val published: PublishedWorkspaceGeneration = testPublishedWorkspaceGeneration(),
    private val onReconcile:
    suspend (io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest) ->
    PublishedWorkspaceGeneration =
        { published },
) : WorkspaceTransitionPort {
    override suspend fun reconcile(
        request: io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest,
    ): WorkspaceTransitionOutcome =
        WorkspaceTransitionOutcome.Published(onReconcile(request))

    override suspend fun <T> mutate(
        signal: io.github.amichne.kast.workspace.contract.WorkspaceSignal,
        detail: String,
        operation: suspend () -> T,
    ): WorkspaceMutationTransitionOutcome<T> {
        val value = operation()
        return WorkspaceMutationTransitionOutcome.Completed(value, published)
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
    val generation: EvidenceGeneration,
) : OpenWorkspacePublication

private class TestPreparedWorkspacePublication(
    val generation: EvidenceGeneration,
    val identity: WorkspaceStateIdentity,
) : PreparedWorkspacePublication

internal data class TestWorkspacePublicationCommit(
    override val publication: PublishedWorkspaceGeneration,
) : WorkspacePublicationCommit

internal fun testWorkspacePublicationCommit(
    publication: PublishedWorkspaceGeneration,
): WorkspacePublicationCommit = TestWorkspacePublicationCommit(publication)

private fun OpenWorkspacePublication.testPublication(): TestOpenWorkspacePublication =
    this as? TestOpenWorkspacePublication
    ?: error("Open workspace generation belongs to another test authority")

private fun PreparedWorkspacePublication.testPublication(): TestPreparedWorkspacePublication =
    this as? TestPreparedWorkspacePublication
    ?: error("Prepared workspace generation belongs to another test authority")

internal fun testPublishedWorkspaceGeneration(
    generation: WorkspaceSemanticGeneration = WorkspaceSemanticGeneration(1),
    identity: WorkspaceStateIdentity = WorkspaceStateIdentity("test-workspace-state"),
): PublishedWorkspaceGeneration = PublishedWorkspaceGeneration(
    generation = evidenceGeneration(generation.value),
    identity = identity,
)

private fun testPublishedWorkspaceGeneration(
    generation: EvidenceGeneration,
    identity: WorkspaceStateIdentity,
): PublishedWorkspaceGeneration = PublishedWorkspaceGeneration(generation, identity)

private fun evidenceGeneration(raw: Long): EvidenceGeneration = when (
    val parsed = EvidenceGeneration.parse(raw)
) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error(parsed.failure)
}

internal data object TestAddDeclarationPlanPersistence : AddDeclarationPlanPersistence {
    override fun persist(plan: PlannedAddDeclaration): PersistAddDeclarationPlanResult =
        PersistAddDeclarationPlanResult.Stored(
            PersistedAddDeclarationPlan.awaitingApproval(plan),
        )
}
