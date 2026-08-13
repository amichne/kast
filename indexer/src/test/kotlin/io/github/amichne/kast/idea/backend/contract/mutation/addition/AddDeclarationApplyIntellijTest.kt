package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
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
import io.github.amichne.kast.change.contract.AddDeclarationMutationProgress
import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.AddDeclarationRevalidationObservation
import io.github.amichne.kast.change.contract.AddDeclarationSourceProvenance
import io.github.amichne.kast.change.contract.AddDeclarationTargetWritability
import io.github.amichne.kast.change.contract.PlannedAddDeclaration
import io.github.amichne.kast.change.contract.RevalidatedAddDeclaration
import io.github.amichne.kast.change.journal.contract.AddDeclarationApplyJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.contract.AddDeclarationPlanStage
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.ApproveAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.BeginAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApply
import io.github.amichne.kast.change.journal.contract.CompleteAddDeclarationApplyResult
import io.github.amichne.kast.change.journal.contract.LoadAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.contract.PersistedAddDeclarationPlan
import io.github.amichne.kast.change.journal.contract.RawAddDeclarationPlanApprovalEvidence
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecovery
import io.github.amichne.kast.change.journal.contract.PrepareAddDeclarationRecoveryResult
import io.github.amichne.kast.change.journal.contract.StoreAddDeclarationPlanResult
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenResult
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistenceService
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparer
import io.github.amichne.kast.change.recovery.filesystem.FilesystemAddDeclarationRecoveryPreparerOpenResult
import io.github.amichne.kast.change.recovery.service.AddDeclarationRecoveryPreparationService
import io.github.amichne.kast.change.journal.contract.JournaledAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.service.PrepareApprovedAddDeclarationRecoveryResult
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

@TestApplication
internal class AddDeclarationApplyIntellijTest : ExactAdditionPlanningTestSupport() {
    private val applyTargetFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ApplyTarget.kt",
        "package demo\n\nclass ApplyTargetAnchor\n",
    )

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `real recovery and journal apply exact postimage to only declared target`() = runBlocking {
        val fixture = fixture("fun applied(value: String): String = value")
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(project),
        )

        val outcome = service.apply(fixture.recovery)
        val applied = assertInstanceOf<ApplyRecoveryPreparedAddDeclarationResult.AppliedUnverified>(
            outcome,
            "unexpected apply outcome: $outcome",
        )

        assertEquals(fixture.plan, applied.record.plan)
        assertEquals(AddDeclarationPlanStage.APPLIED_UNVERIFIED, applied.record.stage)
        assertEquals(
            setOf(fixture.target.toString()),
            applied.closure.observation.changedDocumentPaths.map { it.value }.toSet(),
        )
        assertArrayEquals(
            fixture.plan.expectedFile.postimage.copyBytes(),
            Files.readAllBytes(fixture.target),
        )
        val addedParameterCount = readAction {
            fixture.targetFile.declarations
                .filterIsInstance<KtNamedFunction>()
                .singleOrNull { declaration ->
                    declaration.name == fixture.plan.expectedSemanticDelta.declarationName
                }
                ?.valueParameters
                ?.size
        }
        assertEquals(1, addedParameterCount)
        val reopened = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            fixture.journal.load(fixture.plan.planId),
        ).record
        assertEquals(applied.record, reopened)
    }

    @Test
    fun `stale preimage leaves durable apply admission and performs no additional write`() = runBlocking {
        val fixture = fixture("fun staleRejected(): Unit = Unit")
        val moved = moveTargetOutsideExecutor(fixture, "// external movement")
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(project),
        )

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation,
        >(
            service.apply(fixture.recovery),
        )
        val physical = assertInstanceOf<AddDeclarationRecoveryRequiredFailure.PhysicalBeforeMutation>(
            recovery.failure,
        )

        assertEquals(AddDeclarationApplyPreconditionFailure.TARGET_PREIMAGE_MISMATCH, physical.failure)
        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, recovery.physicalProgress)
        assertEquals(AddDeclarationPlanStage.APPLY_ADMITTED, recovery.admitted.stage)
        assertArrayEquals(moved, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `movement between preparation and write rejects on EDT without entering mutation`() = runBlocking {
        val fixture = fixture("fun raced(): Unit = Unit")
        lateinit var moved: ByteArray
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(
                project = project,
                beforeWriteCommand = { moved = moveTargetOutsideExecutor(fixture, "// raced movement") },
            ),
        )

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation,
        >(
            service.apply(fixture.recovery),
        )

        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, recovery.physicalProgress)
        assertArrayEquals(moved, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `unformatted plan cannot save bytes different from approved postimage`() = runBlocking {
        val fixture = fixture("fun unformatted( value : String ) : String = value")
        val before = Files.readAllBytes(fixture.target)
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(project),
        )

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredBeforeMutation,
        >(
            service.apply(fixture.recovery),
        )
        val physical = assertInstanceOf<AddDeclarationRecoveryRequiredFailure.PhysicalBeforeMutation>(
            recovery.failure,
        )

        assertEquals(
            AddDeclarationApplyPreconditionFailure.APPROVED_POSTIMAGE_UNREPRESENTABLE,
            physical.failure,
        )
        assertEquals(AddDeclarationMutationProgress.NOT_BEGUN, recovery.physicalProgress)
        assertArrayEquals(before, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `adapter exception after durable admission becomes uncertain recovery`() = runBlocking {
        val fixture = fixture("fun adapterFailure(): Unit = Unit")
        val service = AddDeclarationApplicationService(
            journal = fixture.journal,
            executor = IntellijAddDeclarationApplyExecutor(
                project = project,
                beforePreparation = { error("simulated adapter failure") },
            ),
        )

        val recovery = assertInstanceOf<
            ApplyRecoveryPreparedAddDeclarationResult.RecoveryRequiredMutationOutcomeUnknown,
        >(service.apply(fixture.recovery))

        assertEquals(AddDeclarationMutationProgress.MAY_HAVE_BEGUN, recovery.physicalProgress)
        assertInstanceOf<AddDeclarationRecoveryRequiredFailure.PhysicalOutcomeUnknown>(
            recovery.failure,
        )
    }

    private suspend fun fixture(declaration: String): ApplyFixture {
        ensureProjectReady()
        val targetFile = applyTargetFixture.get() as KtFile
        val target = Path.of(targetFile.virtualFile.path).toAbsolutePath().normalize()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), target.toString())
        val stateRoot = Files.createTempDirectory(tempDir.toRealPath(), "apply-").toRealPath()
        val sqlite = assertInstanceOf<SqliteAddDeclarationPlanJournalOpenResult.Opened>(
            SqliteAddDeclarationPlanJournal.open(stateRoot.resolve("journal.db")),
        ).journal
        val journal = CapturingJournal(sqlite)
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
        val stored = assertInstanceOf<LoadAddDeclarationPlanResult.Found>(
            journal.load(storedPlan.planId),
        ).record
        val awaiting = assertInstanceOf<PersistedAddDeclarationPlan.AwaitingApproval>(stored)
        val approved = approve(journal, awaiting)
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
        return ApplyFixture(
            target = target,
            targetFile = targetFile,
            plan = plan,
            journal = journal,
            recovery = recovery,
        )
    }

    private fun approve(
        journal: AddDeclarationPlanJournal,
        awaiting: PersistedAddDeclarationPlan.AwaitingApproval,
    ): PersistedAddDeclarationPlan.Approved {
        val evidence = RawAddDeclarationPlanApprovalEvidence(
            planId = awaiting.plan.planId.value,
            approvedBy = "agent:operator",
            evidenceSha256 = "a".repeat(64),
        ).refine().refined()
        val command = ApproveAddDeclarationPlan.admit(
            planId = awaiting.plan.planId,
            expectedVersion = awaiting.version,
            evidence = evidence,
        ).refined()
        return assertInstanceOf<ApproveAddDeclarationPlanResult.Approved>(
            journal.approve(command),
        ).record
    }

    private fun moveTargetOutsideExecutor(fixture: ApplyFixture, comment: String): ByteArray {
        val moved = Files.readAllBytes(fixture.target) + "\n$comment".toByteArray(StandardCharsets.UTF_8)
        Files.write(fixture.target, moved)
        return moved
    }

    private class CapturingJournal(
        private val delegate: AddDeclarationApplyJournal,
    ) : AddDeclarationApplyJournal {
        var storedPlan: PlannedAddDeclaration? = null
            private set

        override fun store(plan: PlannedAddDeclaration): StoreAddDeclarationPlanResult {
            val result = delegate.store(plan)
            if (result !is StoreAddDeclarationPlanResult.Rejected) storedPlan = plan
            return result
        }

        override fun load(planId: AddDeclarationPlanId): LoadAddDeclarationPlanResult =
            delegate.load(planId)

        override fun approve(command: ApproveAddDeclarationPlan): ApproveAddDeclarationPlanResult =
            delegate.approve(command)

        override fun prepareRecovery(
            command: PrepareAddDeclarationRecovery,
        ): PrepareAddDeclarationRecoveryResult = delegate.prepareRecovery(command)

        override fun beginApply(
            command: BeginAddDeclarationApply,
        ): BeginAddDeclarationApplyResult = delegate.beginApply(command)

        override fun completeApply(
            command: CompleteAddDeclarationApply,
        ): CompleteAddDeclarationApplyResult = delegate.completeApply(command)
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        assertInstanceOf<Refinement.Refined<T>>(this).value

    private data class ApplyFixture(
        val target: Path,
        val targetFile: KtFile,
        val plan: PlannedAddDeclaration,
        val journal: AddDeclarationApplyJournal,
        val recovery: JournaledAddDeclarationRecovery,
    )
}
