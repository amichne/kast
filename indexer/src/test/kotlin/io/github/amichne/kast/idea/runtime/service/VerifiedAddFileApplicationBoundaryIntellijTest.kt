package io.github.amichne.kast.idea

import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.protocol.PartialApplyException
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.mutation.operations.PlanAttempt
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileProofAdmission
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileNonDestructiveObservation
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileRecoveryPrepared
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileResult
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileSourceApplication
import io.github.amichne.kast.idea.backend.mutation.operations.VerifiedAddFileVcsWriteAuthorized
import io.github.amichne.kast.idea.backend.mutation.operations.prepareVerifiedAddFileRecovery
import io.github.amichne.kast.idea.backend.mutation.operations.planVerifiedAddFile
import io.github.amichne.kast.idea.backend.mutation.operations.toResult
import io.github.amichne.kast.server.change.RevalidatedVerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFileAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileIntent
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

@TestApplication
internal class VerifiedAddFileApplicationBoundaryIntellijTest : ExactAdditionPlanningTestSupport() {
    @Test
    fun `exact-content target racing before create never acquires delete recovery authority`() =
        runBlocking {
            ensureProjectReady()
            val sourceRoot = sourceRoot()
            val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
            val target = sourceRoot.resolve("ForeignExactImage.kt")
            val content = "package demo\n\nclass ForeignExactImage\n"
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
            val revalidated = assertInstanceOf<VerifiedAddFileAdmission.Admitted<RevalidatedVerifiedAddFilePlan>>(
                RevalidatedVerifiedAddFilePlan.admit(planned, planned.exact),
            ).value
            val recovery = assertInstanceOf<
                VerifiedAddFileProofAdmission.Admitted<VerifiedAddFileRecoveryPrepared>,
            >(
                prepareVerifiedAddFileRecovery(workspaceRoot, revalidated),
            ).value
            val authorized = assertInstanceOf<VerifiedAddFileAdmission.Admitted<VerifiedAddFileVcsWriteAuthorized>>(
                VerifiedAddFileVcsWriteAuthorized.admit(
                    recovery,
                ),
            ).value

            val application = authorized.applyPlannedTarget { query ->
                Files.writeString(target, content)
                backend.applyEdits(query.parsed())
            }
            val unproven = assertInstanceOf<VerifiedAddFileSourceApplication.CommitUnproven>(application)
            val result = assertInstanceOf<VerifiedAddFileResult.NonDestructiveReconciliationRequired>(
                unproven.toResult(),
            )

            assertEquals(VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED, result.failure)
            assertEquals(
                VerifiedAddFileNonDestructiveObservation.TARGET_OBSERVATION_ALLOWED,
                result.observation,
            )
            assertEquals(content, Files.readString(target))
        }

    @Test
    fun `retained scratch recovery never acquires delete recovery authority`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("RetainedScratch.kt")
        val content = "package demo\n\nclass RetainedScratch\n"
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
        val revalidated = assertInstanceOf<VerifiedAddFileAdmission.Admitted<RevalidatedVerifiedAddFilePlan>>(
            RevalidatedVerifiedAddFilePlan.admit(planned, planned.exact),
        ).value
        val recovery = assertInstanceOf<
            VerifiedAddFileProofAdmission.Admitted<VerifiedAddFileRecoveryPrepared>,
        >(
            prepareVerifiedAddFileRecovery(workspaceRoot, revalidated),
        ).value
        val authorized = assertInstanceOf<VerifiedAddFileAdmission.Admitted<VerifiedAddFileVcsWriteAuthorized>>(
            VerifiedAddFileVcsWriteAuthorized.admit(recovery),
        ).value
        val retained = target.resolveSibling(".kast-retained-scratch")

        val application = authorized.applyPlannedTarget {
            throw PartialApplyException(
                details = mapOf(
                    "failedFile" to target.toString(),
                    "appliedFiles" to target.toString(),
                    "createdFiles" to target.toString(),
                    "deletedFiles" to "",
                    "recoveryFilePathCount" to "1",
                    "recoveryFilePath.0" to retained.toString(),
                ),
            )
        }

        val unproven = assertInstanceOf<VerifiedAddFileSourceApplication.CommitUnproven>(application)
        assertEquals(VerifiedAddFileFailure.SOURCE_APPLICATION_FAILED, unproven.failure)
        assertEquals(
            VerifiedAddFileNonDestructiveObservation.COMMIT_EVIDENCE_INCOMPLETE,
            unproven.observation,
        )
    }

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        assertInstanceOf<VerifiedAddFileRefinement.Refined<T>>(refinement).value
}
