package io.github.amichne.kast.idea

import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyMode
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

@TestApplication
internal class VerifiedAddFilePreAdmissionVersionIntellijTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `pre-admission rejections use initial version instead of untrusted requested version`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val operations = backend(
            workspaceRoot,
            psiGeneration = { 1L },
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
        val mismatchedWorkspace = workspaceRoot.parent.resolve("different-workspace").toAbsolutePath().normalize()
        val requests = listOf(
            VerifiedAddFileFailure.WORKSPACE_MISMATCH to applyRequest(mismatchedWorkspace),
            VerifiedAddFileFailure.PLAN_NOT_FOUND to applyRequest(workspaceRoot),
        )

        requests.forEach { (expectedFailure, request) ->
            val rejected = assertInstanceOf<VerifiedAddFileApplyResult.Rejected>(
                operations.apply(request),
            )

            assertEquals(expectedFailure, rejected.failure)
            assertEquals(0L, rejected.planVersion.value)
            assertInstanceOf<VerifiedAddFileApplyResultAdmission.Admitted>(
                AdmittedVerifiedAddFileApplyResult.admit(rejected),
            )
        }
    }

    @Test
    fun `recovery mode cannot initiate a fresh awaiting approval apply`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("demo/RecoveryMustNotApply.kt")
        val content = "package demo\n\nclass RecoveryMustNotApply\n"
        Files.createDirectories(target.parent)
        val generation = AtomicLong(1L)
        val operations = backend(
            workspaceRoot,
            psiGeneration = generation::get,
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = {
                    testPublishedWorkspaceGeneration(WorkspaceSemanticGeneration(generation.incrementAndGet()))
                },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(
                VerifiedAddFilePlanRequest(
                    workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
                    targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
                    proposedContent = refined(VerifiedAddFileContent.refine(content)),
                ),
            ),
        )

        val rejected = assertInstanceOf<VerifiedAddFileApplyResult.Rejected>(
            operations.apply(recoveryRequest(workspaceRoot, planned)),
        )

        assertEquals(VerifiedAddFileProgress.INTENT_ADMISSION, rejected.progress)
        assertEquals(VerifiedAddFileFailure.PLAN_NOT_FOUND, rejected.failure)
        assertFalse(Files.exists(target), "recovery-only authority must not create a fresh target")
    }

    private fun applyRequest(workspaceRoot: Path) = VerifiedAddFileApplyRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        planId = refined(VerifiedAddFilePlanId.refine("af-" + "9".repeat(64))),
        expectedVersion = refined(VerifiedAddFilePlanVersion.refine(7L)),
        mode = VerifiedAddFileApplyMode.APPLY,
        approvalEvidence = VerifiedAddFileApprovalEvidence(
            approvedBy = refined(VerifiedAddFileApprovedBy.refine("kast-public-cli")),
            evidenceSha256 = refined(
                VerifiedAddFileApprovalEvidenceSha256.refine("b".repeat(64)),
            ),
        ),
    )

    private fun recoveryRequest(
        workspaceRoot: Path,
        planned: VerifiedAddFilePlanResult.Planned,
    ) = VerifiedAddFileApplyRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        planId = planned.planId,
        expectedVersion = planned.planVersion,
        mode = VerifiedAddFileApplyMode.RECOVER,
        approvalEvidence = VerifiedAddFileApprovalEvidence(
            approvedBy = refined(VerifiedAddFileApprovedBy.refine("kast-public-cli")),
            evidenceSha256 = refined(
                VerifiedAddFileApprovalEvidenceSha256.refine(
                    FileHashing.sha256(
                        "kast-public-cli\nworkspaceRoot=${workspaceRoot.toAbsolutePath().normalize()}\n" +
                            "planId=${planned.planId.value}\nexpectedVersion=${planned.planVersion.value}\n",
                    ),
                ),
            ),
        ),
    )

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        assertInstanceOf<VerifiedAddFileRefinement.Refined<T>>(refinement).value
}
