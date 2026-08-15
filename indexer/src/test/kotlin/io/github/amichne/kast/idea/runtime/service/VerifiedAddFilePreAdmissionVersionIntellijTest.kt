package io.github.amichne.kast.idea

import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun applyRequest(workspaceRoot: Path) = VerifiedAddFileApplyRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        planId = refined(VerifiedAddFilePlanId.refine("af-" + "9".repeat(64))),
        expectedVersion = refined(VerifiedAddFilePlanVersion.refine(7L)),
        approvalEvidence = VerifiedAddFileApprovalEvidence(
            approvedBy = refined(VerifiedAddFileApprovedBy.refine("kast-public-cli")),
            evidenceSha256 = refined(
                VerifiedAddFileApprovalEvidenceSha256.refine("b".repeat(64)),
            ),
        ),
    )

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        assertInstanceOf<VerifiedAddFileRefinement.Refined<T>>(refinement).value
}
