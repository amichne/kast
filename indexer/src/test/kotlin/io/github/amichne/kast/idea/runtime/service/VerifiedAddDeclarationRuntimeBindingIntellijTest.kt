package io.github.amichne.kast.idea

import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenResult
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistenceService
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparerOpenResult
import io.github.amichne.kast.change.verify.intellij.IntellijPublishedWorkspaceGenerationAuthority
import io.github.amichne.kast.idea.backend.mutation.verifiedAddDeclarationOperations
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApplyResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddDeclarationApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddDeclarationDeclarationKind
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanRequest
import io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanResult
import io.github.amichne.kast.server.change.VerifiedAddDeclarationProposedDeclaration
import io.github.amichne.kast.server.change.VerifiedAddDeclarationTargetPath
import io.github.amichne.kast.server.change.VerifiedAddDeclarationWireRefinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@TestApplication
internal class VerifiedAddDeclarationRuntimeBindingIntellijTest : ExactAdditionPlanningTestSupport() {
    private val targetFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "PublicVerifiedTarget.kt",
        "package demo\n\nclass PublicVerifiedTarget\n",
    )

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `operation binding completes durable v5 verification through live PSI and K2`() = runBlocking {
        ensureProjectReady()
        val target = Path.of(targetFixture.get().virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
        val journal = assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(tempDir.resolve("verified-operation.db")),
        ).journal
        val backend = backend(
            workspaceRoot = workspaceRoot,
            workspaceModelReader = model(workspaceRoot, sourceRoot),
            addDeclarationPlanPersistenceService = AddDeclarationPlanPersistenceService(journal),
        )
        val plannedGeneration = AtomicLong(-1)
        val currentPublication = AtomicReference<PublishedWorkspaceGenerationState>(
            PublishedWorkspaceGenerationState.Unpublished,
        )
        val transitions = publishingTransition(plannedGeneration, currentPublication)
        val publicationAuthority = IntellijPublishedWorkspaceGenerationAuthority(currentPublication::get)
        val recoveryPreparer = assertInstanceOf<FilesystemAddDeclarationRecoveryPreparerOpenResult.Opened>(
            FilesystemAddDeclarationRecoveryPreparer.open(
                Files.createDirectory(tempDir.resolve("recovery")).toRealPath(),
            ),
        ).preparer
        val operations = backend.verifiedAddDeclarationOperations(
            workspaceRoot = workspaceRoot,
            journal = journal,
            transitions = transitions,
            publications = publicationAuthority,
            recoveryPreparer = recoveryPreparer,
            runtime = documentedIntellijIdeaRuntime,
        )
        val declaration = "fun publicVerified() {}"
        val planned = assertInstanceOf<VerifiedAddDeclarationPlanResult.Planned>(
            operations.plan(
                VerifiedAddDeclarationPlanRequest(
                    NormalizedPath.ofAbsolute(workspaceRoot),
                    refined(VerifiedAddDeclarationTargetPath.refine(target.toString())),
                    refined(VerifiedAddDeclarationProposedDeclaration.refine(declaration)),
                ),
            ),
        )
        plannedGeneration.set(planned.preview.generation.value)

        val outcome = operations.apply(
            VerifiedAddDeclarationApplyRequest(
                workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
                planId = planned.planId,
                expectedVersion = planned.planVersion,
                approvalEvidence = VerifiedAddDeclarationApprovalEvidence(
                    refined(VerifiedAddDeclarationApprovedBy.refine("kast-public-cli")),
                    refined(VerifiedAddDeclarationApprovalEvidenceSha256.refine("a".repeat(64))),
                ),
            ),
        )
        val verified = assertInstanceOf<VerifiedAddDeclarationApplyResult.Verified>(
            outcome,
            "unexpected verified add-declaration outcome: $outcome",
        )

        assertEquals(5L, verified.planVersion.value)
        assertEquals(VerifiedAddDeclarationDeclarationKind.FUNCTION, verified.identity.declarationKind)
        assertEquals("publicVerified", verified.identity.declarationName.value)
        assertEquals(target.toString(), verified.identity.targetPath.value)
        assertTrue(Files.readString(target).contains(declaration))
        val durable = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            journal.load(verified.planId.toChangePlanId()),
        ).record
        assertEquals(AddDeclarationPlanStage.VERIFIED, durable.stage)
    }

    private fun publishingTransition(
        plannedGeneration: AtomicLong,
        current: AtomicReference<PublishedWorkspaceGenerationState>,
    ): WorkspaceTransitionPort = object : WorkspaceTransitionPort {
        override suspend fun reconcile(request: WorkspaceTransitionRequest): WorkspaceTransitionOutcome {
            assertInstanceOf<WorkspaceTransitionRequest.SourceFiles>(request)
            return WorkspaceTransitionOutcome.Published(current.published())
        }

        override suspend fun <Value> mutate(
            signal: WorkspaceSignal,
            detail: String,
            operation: suspend () -> Value,
        ): WorkspaceMutationTransitionOutcome<Value> {
            assertEquals(WorkspaceSignal.Source, signal)
            assertTrue(detail.isNotBlank())
            val value = operation()
            val publication = PublishedWorkspaceGeneration(
                EvidenceGeneration.parse(plannedGeneration.get() + 1L).refined(),
                WorkspaceStateIdentity("public-verified-add-declaration-g1"),
            )
            current.set(PublishedWorkspaceGenerationState.Published(publication))
            return WorkspaceMutationTransitionOutcome.Completed(value, publication)
        }
    }

    private fun AtomicReference<PublishedWorkspaceGenerationState>.published(): PublishedWorkspaceGeneration =
        assertInstanceOf<PublishedWorkspaceGenerationState.Published>(get()).publication

    private fun io.github.amichne.kast.server.change.VerifiedAddDeclarationPlanId.toChangePlanId() =
        assertInstanceOf<Refinement.Refined<io.github.amichne.kast.change.contract.AddDeclarationPlanId>>(
            io.github.amichne.kast.change.contract.AddDeclarationPlanId.parse(value),
        ).value

    private fun <T> refined(refinement: VerifiedAddDeclarationWireRefinement<T>): T =
        assertInstanceOf<VerifiedAddDeclarationWireRefinement.Refined<T>>(refinement).value

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value
}
