package io.github.amichne.kast.idea

import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.change.apply.intellij.IntellijAddDeclarationApplyExecutor
import io.github.amichne.kast.change.apply.service.AddDeclarationApplicationService
import io.github.amichne.kast.change.apply.service.AddDeclarationRecoveryRequiredFailure
import io.github.amichne.kast.change.apply.service.ApplyRecoveryPreparedAddDeclarationResult
import io.github.amichne.kast.change.apply.spi.AddDeclarationApplyPreconditionFailure
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenResult
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistenceService
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparerOpenResult
import io.github.amichne.kast.change.recovery.service.AddDeclarationRecoveryPreparationService
import io.github.amichne.kast.change.recovery.service.PrepareApprovedAddDeclarationRecoveryResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@TestApplication
internal class AddDeclarationApplyReviewRegressionIntellijTest : ExactAdditionPlanningTestSupport() {
    private val applyTargetFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ApplyReviewTarget.kt",
        "package demo\n\nclass ApplyReviewTargetAnchor\n",
    )

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `symlink swap cannot escape approved target`() = runBlocking {
        val fixture = fixture("fun symlinkEscaped(): Unit = Unit")
        val original = Files.readAllBytes(fixture.target)
        val external = tempDir.resolve("external-target.kt")
        Files.write(external, original)
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(
                project = project,
                runtime = documentedIntellijIdeaRuntime,
                beforeWriteCommand = {
                    Files.delete(fixture.target)
                    Files.createSymbolicLink(fixture.target, external)
                },
            ),
        )

        try {
            val recovery = assertInstanceOf<
                ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation,
                >(service.apply(fixture.recovery))
            val physical = assertInstanceOf<
                AddDeclarationRecoveryRequiredFailure.PhysicalBeforeMutation,
                >(recovery.failure)
            assertEquals(AddDeclarationApplyPreconditionFailure.TARGET_INVALIDATED, physical.failure)
            assertArrayEquals(original, Files.readAllBytes(external))
        } finally {
            Files.deleteIfExists(fixture.target)
            Files.write(fixture.target, original)
        }
    }

    @Test
    fun `executor preserves coroutine cancellation`() = runBlocking {
        val fixture = fixture("fun cancellationProtected(): Unit = Unit")
        val cancellation = CancellationException("cancel before preparation")
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(
                project = project,
                runtime = documentedIntellijIdeaRuntime,
                beforePreparation = { throw cancellation },
            ),
        )
        var observed: CancellationException? = null

        try {
            service.apply(fixture.recovery)
        } catch (failure: CancellationException) {
            observed = failure
        }

        assertSame(cancellation, observed)
    }

    private suspend fun fixture(declaration: String): AddDeclarationApplyIntellijFixture {
        ensureProjectReady()
        val targetFile = applyTargetFixture.get() as KtFile
        val target = Path.of(targetFile.virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
        val stateRoot = Files.createTempDirectory(tempDir.toRealPath(), "apply-review-").toRealPath()
        val sqlite = assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(stateRoot.resolve("journal.db")),
        ).journal
        val journal = CapturingAddDeclarationApplyJournal(sqlite)
        val backend = backend(
            workspaceRoot = workspaceRoot,
            workspaceModelReader = model(workspaceRoot, sourceRoot),
            addDeclarationPlanPersistenceService = AddDeclarationPlanPersistenceService(journal),
        )
        val before = Files.readAllBytes(target)
        backend.planAddDeclaration(
            AddDeclarationPlanQuery(
                targetPath = AdditionTargetPath.parse(target.toString()),
                expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                proposedDeclaration = declaration,
            ),
        )
        val storedPlan = requireNotNull(journal.storedPlan)
        val awaiting = assertInstanceOf<PersistedAddDeclarationPlan.AwaitingApproval>(
            assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
                journal.load(storedPlan.planId),
            ).record,
        )
        val approved = approveAddDeclarationPlan(journal, awaiting)
        val plan = approved.plan
        val revalidated = RevalidatedAddDeclaration.admit(
            plan,
            AddDeclarationRevalidationObservation.observe(
                generation = EvidenceGeneration.parse(plan.generation.value).refined(),
                target = plan.target,
                currentFile = plan.expectedFile.preimage,
                provenance = AddDeclarationSourceProvenance.AUTHORED,
                writability = AddDeclarationTargetWritability.WRITABLE,
            ).refined(),
        ).refined()
        val preparer = assertInstanceOf<FilesystemAddDeclarationRecoveryPreparerOpenResult.Opened>(
            FilesystemAddDeclarationRecoveryPreparer.open(
                Files.createDirectory(stateRoot.resolve("recovery")).toRealPath(),
            ),
        ).preparer
        val recovery = assertInstanceOf<PrepareApprovedAddDeclarationRecoveryResult.Prepared>(
            AddDeclarationRecoveryPreparationService(journal, preparer).prepare(approved, revalidated),
        ).recovery
        return AddDeclarationApplyIntellijFixture(target, targetFile, plan, journal, recovery)
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value
}
