package io.github.amichne.kast.idea

import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.mutation.operations.PersistedVerifiedAddFileLifecycle
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalRead
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileJournalWrite
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFilePlanJournal
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileProofAdmission
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileRecoveryPrepared
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileRecoveryId
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyMode
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

@TestApplication
internal class VerifiedAddFileUnknownOutcomeRevalidationIntellijTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `absent unknown write outcome revalidates before restart recovery`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("StaleUnknownOutcome.kt")
        val content = "package demo\n\nclass StaleUnknownOutcome\n"
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
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            backend.verifiedAddFileOperations(workspaceRoot).plan(planRequest(workspaceRoot, target, content)),
        )
        val journal = VerifiedAddFilePlanJournal(backend.workspaceIdentity.workspaceIdentity)
        val persisted = assertInstanceOf<VerifiedAddFileJournalRead.Loaded>(
            journal.load(planned.planId),
        ).plan
        val recovery = assertInstanceOf<VerifiedAddFileProofAdmission.Admitted<VerifiedAddFileRecoveryPrepared>>(
            VerifiedAddFileRecoveryPrepared.readmitPersisted(
                persisted.planned,
                verifiedAddFileRecoveryId(persisted.planned),
            ),
        ).value
        persisted.lifecycle = PersistedVerifiedAddFileLifecycle.ApplyOutcomeUnknown(recovery)
        assertEquals(VerifiedAddFileJournalWrite.Stored, journal.store(persisted))
        generation.incrementAndGet()

        val outcome = backend.verifiedAddFileOperations(workspaceRoot).apply(applyRequest(workspaceRoot, planned))

        val rejected = assertInstanceOf<VerifiedAddFileApplyResult.Rejected>(outcome)
        assertEquals(VerifiedAddFileProgress.REVALIDATION, rejected.progress)
        assertEquals(VerifiedAddFileFailure.PLAN_REVALIDATION_FAILED, rejected.failure)
        assertFalse(Files.exists(target), "stale pre-write authority must not create the target")
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
        mode = VerifiedAddFileApplyMode.APPLY,
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
