package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.server.change.NativeVerifiedAddFileOperations
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import java.nio.file.Files
import java.nio.file.Path
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
    fun `write outcome retains recovery authority across native operations recreation`() = runBlocking {
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
