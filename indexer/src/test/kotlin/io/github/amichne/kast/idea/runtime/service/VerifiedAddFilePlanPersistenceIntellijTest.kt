package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.mutation.operations.PersistedVerifiedAddFileLifecycle
import io.github.amichne.kast.idea.backend.mutation.operations.PersistedVerifiedAddFilePlan
import io.github.amichne.kast.idea.backend.mutation.operations.PlanAttempt
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalDirectoryDurability
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalDirectoryDurabilityBarrier
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalFailure
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalRead
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalWrite
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFilePlanJournal
import io.github.amichne.kast.idea.backend.mutation.operations.planVerifiedAddFile
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFilePlanId
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.server.change.NativeVerifiedAddFileOperations
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileIntent
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

@TestApplication
internal class VerifiedAddFilePlanPersistenceIntellijTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `stale initial journal publication cannot replace a newer terminal lifecycle`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("ConcurrentInitialPlan.kt")
        val content = "package demo\n\nclass ConcurrentInitialPlan\n"
        val generation = AtomicLong(1L)
        val backend = backend(
            workspaceRoot,
            psiGeneration = generation::get,
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = {
                    testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(generation.incrementAndGet()))
                },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        )
        val intent = VerifiedAddFileIntent(
            workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
            targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
            content = refined(VerifiedAddFileContent.refine(content)),
        )
        val planned = assertInstanceOf<PlanAttempt.Planned>(
            planVerifiedAddFile(backend, intent, VerifiedAddFileProgress.PLANNING),
        ).plan
        val staleInitial = PersistedVerifiedAddFilePlan(
            verifiedAddFilePlanId(planned),
            refined(VerifiedAddFilePlanVersion.refine(0L)),
            planned,
        )
        val staleJournal = VerifiedAddFilePlanJournal(backend.workspaceIdentity.workspaceIdentity)
        assertEquals(VerifiedAddFileJournalWrite.Stored, staleJournal.store(staleInitial))
        val operations = backend.verifiedAddFileOperations(workspaceRoot)
        val publicPlan = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(planRequest(workspaceRoot, target, content)),
        )
        assertInstanceOf<VerifiedAddFileApplyResult.Verified>(
            operations.apply(applyRequest(workspaceRoot, publicPlan)),
        )

        assertEquals(VerifiedAddFileJournalWrite.Stored, staleJournal.store(staleInitial))

        val reloaded = assertInstanceOf<VerifiedAddFileJournalRead.Loaded>(
            VerifiedAddFilePlanJournal(backend.workspaceIdentity.workspaceIdentity).load(publicPlan.planId),
        )
        assertInstanceOf<PersistedVerifiedAddFileLifecycle.Terminal.Verified>(reloaded.plan.lifecycle)
        Unit
    }

    @Test
    fun `post-effect journal failure returns recovery authority instead of plan rejection`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("TerminalPersistenceFailure.kt")
        val content = "package demo\n\nclass TerminalPersistenceFailure\n"
        val generation = AtomicLong(1L)
        lateinit var journalDirectory: Path
        val backend = backend(
            workspaceRoot,
            psiGeneration = generation::get,
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = {
                    Files.setPosixFilePermissions(journalDirectory, setOf(OWNER_READ, OWNER_EXECUTE))
                    testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(generation.incrementAndGet()))
                },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        )
        journalDirectory = backend.workspaceIdentity.workspaceIdentity.workspaceCacheDirectoryPath
            .resolve("verified-add-file-plans")
        val operations = backend.verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(planRequest(workspaceRoot, target, content)),
        )
        val originalPermissions = Files.getPosixFilePermissions(journalDirectory)

        val outcome = try {
            operations.apply(applyRequest(workspaceRoot, planned))
        } finally {
            Files.setPosixFilePermissions(journalDirectory, originalPermissions)
        }

        val recovery = assertInstanceOf<VerifiedAddFileApplyResult.RecoveryRequired>(
            outcome,
            "post-effect persistence failure must retain recovery authority: $outcome",
        )
        assertEquals(planned.planId.value, recovery.recoveryId.value)
        assertEquals(content, Files.readString(target))
    }

    @Test
    fun `journal store rejects after replacement until its directory is durable`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("DirectoryDurability.kt")
        val content = "package demo\n\nclass DirectoryDurability\n"
        val backend = backend(
            workspaceRoot,
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        )
        val intent = VerifiedAddFileIntent(
            workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
            targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
            content = refined(VerifiedAddFileContent.refine(content)),
        )
        val planned = assertInstanceOf<PlanAttempt.Planned>(
            planVerifiedAddFile(backend, intent, VerifiedAddFileProgress.PLANNING),
        ).plan
        val persisted = PersistedVerifiedAddFilePlan(
            verifiedAddFilePlanId(planned),
            refined(VerifiedAddFilePlanVersion.refine(0L)),
            planned,
        )
        val barrier = VerifiedAddFileJournalDirectoryDurabilityBarrier { directory ->
            assertTrue(
                Files.isRegularFile(directory.resolve("${persisted.planId.value}.json")),
                "the atomic replacement must precede parent-directory synchronization",
            )
            VerifiedAddFileJournalDirectoryDurability.UNAVAILABLE
        }

        val result = VerifiedAddFilePlanJournal(
            backend.workspaceIdentity.workspaceIdentity,
            barrier,
        ).store(persisted)

        assertEquals(
            VerifiedAddFileJournalWrite.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `planned authority survives native operations recreation`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("RestartedPlan.kt")
        val content = "package demo\n\nclass RestartedPlan\n"
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations(workspaceRoot, sourceRoot).plan(planRequest(workspaceRoot, target, content)),
        )

        val outcome = operations(workspaceRoot, sourceRoot).apply(applyRequest(workspaceRoot, planned))

        assertInstanceOf<VerifiedAddFileApplyResult.Verified>(
            outcome,
            "recreated native owner must re-admit the durable strong plan: $outcome",
        )
        assertEquals(content, Files.readString(target))
    }

    @Test
    fun `retained recovery authority survives native operations recreation`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("RestartedRecovery.kt")
        val content = "package demo\n\nclass RestartedRecovery\n"
        val interrupted = backend(
            workspaceRoot,
            psiGeneration = { PsiModificationTracker.getInstance(project).modificationCount },
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = { throw ProcessCanceledException() },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            interrupted.plan(planRequest(workspaceRoot, target, content)),
        )
        assertInstanceOf<VerifiedAddFileApplyResult.RecoveryRequired>(
            interrupted.apply(applyRequest(workspaceRoot, planned)),
        )
        assertTrue(Files.exists(target))

        val recovered = operations(workspaceRoot, sourceRoot).apply(applyRequest(workspaceRoot, planned))

        assertInstanceOf<VerifiedAddFileApplyResult.RolledBack>(
            recovered,
            "recreated native owner must consume retained recovery authority: $recovered",
        )
        assertFalse(Files.exists(target))
    }

    @Test
    fun `unknown write outcome terminalizes after user removes the reconciled target`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("InterruptedAfterWrite.kt")
        val content = "package demo\n\nclass InterruptedAfterWrite\n"
        val interrupted = backend(
            workspaceRoot,
            psiGeneration = { PsiModificationTracker.getInstance(project).modificationCount },
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = { throw SimulatedIndexerExit() },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            interrupted.plan(planRequest(workspaceRoot, target, content)),
        )

        val interruptedOutcome = runCatching {
            interrupted.apply(applyRequest(workspaceRoot, planned))
        }

        assertInstanceOf<SimulatedIndexerExit>(interruptedOutcome.exceptionOrNull())
        assertEquals(content, Files.readString(target))
        val recovered = operations(workspaceRoot, sourceRoot).apply(applyRequest(workspaceRoot, planned))
        val reconciliation = assertInstanceOf<VerifiedAddFileApplyResult.ReconciliationRequired>(
            recovered,
            "recreated native owner must retain unknown write outcome authority: $recovered",
        )
        assertEquals(planned.planId, reconciliation.planId)
        assertEquals(content, Files.readString(target))

        Files.delete(target)
        val terminal = operations(workspaceRoot, sourceRoot).apply(applyRequest(workspaceRoot, planned))

        assertInstanceOf<VerifiedAddFileApplyResult.RolledBack>(
            terminal,
            "published user-resolved absence must terminalize non-destructive reconciliation: $terminal",
        )
        assertFalse(Files.exists(target))
    }

    @Test
    fun `already absent target completes retained rollback after publication proof`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("LostDeleteResponse.kt")
        val content = "package demo\n\nclass LostDeleteResponse\n"
        val interrupted = backend(
            workspaceRoot,
            psiGeneration = { PsiModificationTracker.getInstance(project).modificationCount },
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = { throw ProcessCanceledException() },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            interrupted.plan(planRequest(workspaceRoot, target, content)),
        )
        assertInstanceOf<VerifiedAddFileApplyResult.RecoveryRequired>(
            interrupted.apply(applyRequest(workspaceRoot, planned)),
        )
        assertTrue(Files.exists(target))
        Files.delete(target)

        val recovered = operations(workspaceRoot, sourceRoot).apply(applyRequest(workspaceRoot, planned))

        assertInstanceOf<VerifiedAddFileApplyResult.RolledBack>(
            recovered,
            "published absence must terminalize retained rollback authority: $recovered",
        )
        assertFalse(Files.exists(target))
    }

    private fun operations(workspaceRoot: Path, sourceRoot: Path): NativeVerifiedAddFileOperations {
        val generation = AtomicLong(1L)
        return backend(
            workspaceRoot,
            psiGeneration = generation::get,
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = {
                    testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(generation.incrementAndGet()))
                },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
    }

    private fun planRequest(workspaceRoot: Path, target: Path, content: String) = VerifiedAddFilePlanRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
        proposedContent = refined(VerifiedAddFileContent.refine(content)),
    )

    private fun applyRequest(
        workspaceRoot: Path,
        planned: VerifiedAddFilePlanResult.Planned,
    ): VerifiedAddFileApplyRequest = VerifiedAddFileApplyRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        planId = planned.planId,
        expectedVersion = refined(VerifiedAddFilePlanVersion.refine(0L)),
        approvalEvidence = VerifiedAddFileApprovalEvidence(
            approvedBy = refined(VerifiedAddFileApprovedBy.refine("kast-public-cli")),
            evidenceSha256 = refined(
                VerifiedAddFileApprovalEvidenceSha256.refine(
                    FileHashing.sha256(
                        buildString {
                            append("kast-public-cli\n")
                            append("workspaceRoot=${workspaceRoot.toAbsolutePath().normalize()}\n")
                            append("planId=${planned.planId.value}\n")
                            append("expectedVersion=${planned.planVersion.value}\n")
                        },
                    ),
                ),
            ),
        ),
    )

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        assertInstanceOf<VerifiedAddFileRefinement.Refined<T>>(refinement).value

    private class SimulatedIndexerExit : Error()
}
