package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

@TestApplication
internal class VerifiedAddFileRevalidationCancellationIntellijTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `apply preserves cancellation from semantic plan revalidation`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("CancelledRevalidation.kt")
        val content = "package demo\n\nclass CancelledRevalidation\n"
        val cancelRevalidation = AtomicBoolean(false)
        val admittedModel = model(workspaceRoot, sourceRoot)
        val operations = backend(
            workspaceRoot,
            workspaceModelReader = {
                if (cancelRevalidation.get()) throw ProcessCanceledException()
                admittedModel()
            },
        ).verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(planRequest(workspaceRoot, target, content)),
        )
        cancelRevalidation.set(true)

        val outcome = operations.apply(applyRequest(workspaceRoot, planned))

        val rejected = assertInstanceOf<VerifiedAddFileApplyResult.Rejected>(outcome)
        assertEquals(VerifiedAddFileProgress.REVALIDATION, rejected.progress)
        assertEquals(VerifiedAddFileFailure.CANCELLED, rejected.failure)
        assertInstanceOf<VerifiedAddFileApplyResultAdmission.Admitted>(
            AdmittedVerifiedAddFileApplyResult.admit(rejected),
        )
        assertFalse(Files.exists(target))
        Unit
    }

    private fun planRequest(
        workspaceRoot: Path,
        target: Path,
        content: String,
    ) = VerifiedAddFilePlanRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
        proposedContent = refined(VerifiedAddFileContent.refine(content)),
    )

    private fun applyRequest(
        workspaceRoot: Path,
        planned: VerifiedAddFilePlanResult.Planned,
    ) = VerifiedAddFileApplyRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        planId = planned.planId,
        expectedVersion = refined(VerifiedAddFilePlanVersion.refine(0L)),
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
