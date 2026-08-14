package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.backend.mutation.operations.verifiedAddFileOperations
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileApplyRequest
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidence
import io.github.amichne.kast.server.change.VerifiedAddFileApprovalEvidenceSha256
import io.github.amichne.kast.server.change.VerifiedAddFileApprovedBy
import io.github.amichne.kast.server.change.VerifiedAddFilePlanRequest
import io.github.amichne.kast.server.change.VerifiedAddFilePlanResult
import io.github.amichne.kast.server.change.VerifiedAddFilePlanStage
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
import org.junit.jupiter.api.io.TempDir

@TestApplication
internal class VerifiedAddFileOperationsIntellijTest : ExactAdditionPlanningTestSupport() {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `creates one authored Kotlin file and returns compiler receipt`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("VerifiedCreated.kt")
        val content = "package demo\n\nclass VerifiedCreated\n"
        val operations = operations(workspaceRoot, sourceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(planRequest(workspaceRoot, target, content)),
        )
        assertTrue(planned.planId.value.startsWith("af-"))
        assertEquals(0L, planned.planVersion.value)
        assertEquals(VerifiedAddFilePlanStage.AWAITING_APPROVAL, planned.stage)
        val outcome = operations.apply(applyRequest(workspaceRoot, planned))

        val verified = assertInstanceOf<VerifiedAddFileApplyResult.Verified>(
            outcome,
            "unexpected verified add-file outcome: $outcome",
        )
        assertEquals(5L, verified.planVersion.value)
        assertEquals(target.toString(), verified.receipt.targetPath.value)
        val packageIdentity = assertInstanceOf<AdditionKotlinPackage.Named>(verified.receipt.packageIdentity)
        assertEquals(listOf("demo"), packageIdentity.segments.map { it.value })
        assertEquals(listOf("VerifiedCreated"), verified.receipt.declarations.map { it.name })
        assertTrue(verified.receipt.generation.value > planned.preview.generation.value)
        assertEquals(content, Files.readString(target))
    }

    @Test
    fun `terminal replay re-admits the original version and approval authority`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("TerminalReplayAuthority.kt")
        val operations = operations(workspaceRoot, sourceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(
                planRequest(
                    workspaceRoot,
                    target,
                    "package demo\n\nclass TerminalReplayAuthority\n",
                ),
            ),
        )
        assertInstanceOf<VerifiedAddFileApplyResult.Verified>(
            operations.apply(applyRequest(workspaceRoot, planned)),
        )

        val stale = assertInstanceOf<VerifiedAddFileApplyResult.Rejected>(
            operations.apply(applyRequest(workspaceRoot, planned, expectedVersion = 1L)),
        )
        assertEquals(VerifiedAddFileFailure.STALE_PLAN_VERSION, stale.failure)
        val unapproved = assertInstanceOf<VerifiedAddFileApplyResult.Rejected>(
            operations.apply(
                applyRequest(
                    workspaceRoot,
                    planned,
                    evidenceSha256 = "0".repeat(64),
                ),
            ),
        )
        assertEquals(VerifiedAddFileFailure.APPROVAL_REJECTED, unapproved.failure)
    }

    @Test
    fun `fails closed when the admitted target parent cannot be canonicalized`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("missing-parent/NotAdmitted.kt")

        val rejected = assertInstanceOf<VerifiedAddFilePlanResult.Rejected>(
            operations(workspaceRoot, sourceRoot).plan(
                planRequest(workspaceRoot, target, "package demo\n\nclass NotAdmitted\n"),
            ),
        )

        assertEquals(VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE, rejected.failure)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `recovery retry consumes retained capability without replanning the present target`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("RetryRecovery.kt")
        val content = "package demo\n\nclass RetryRecovery\n"
        val operations = operations(workspaceRoot, sourceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(planRequest(workspaceRoot, target, content)),
        )
        val connection = ApplicationManager.getApplication().messageBus.connect()
        var replaced = false
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (!replaced && events.any { Path.of(it.path) == target }) {
                        Files.writeString(target, "package demo\n\nclass ForeignImage\n")
                        replaced = true
                    }
                }
            },
        )
        val first = try {
            operations.apply(applyRequest(workspaceRoot, planned))
        } finally {
            connection.disconnect()
        }
        assertInstanceOf<VerifiedAddFileApplyResult.ReconciliationRequired>(
            first,
            "foreign postimage must retain recovery authority: $first",
        )
        assertTrue(Files.exists(target))

        Files.writeString(target, content)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.let { restored ->
            VfsUtil.markDirtyAndRefresh(false, false, false, restored)
        }
        val recovered = operations.apply(applyRequest(workspaceRoot, planned))

        val rolledBack = assertInstanceOf<VerifiedAddFileApplyResult.RolledBack>(
            recovered,
            "retry must recover directly instead of replanning the present target: $recovered",
        )
        assertEquals(VerifiedAddFileFailure.PSI_NOT_ADMITTED, rolledBack.failure)
        assertEquals(5L, rolledBack.planVersion.value)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `rejects a target admitted by more than one imported source owner`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("AmbiguousOwner.kt")
        val backend = backend(
            workspaceRoot,
            workspaceModelReader = model(
                workspaceRoot,
                listOf(
                    association("first", workspaceRoot, ":first", "main", sourceRoot),
                    association("second", workspaceRoot, ":second", "main", sourceRoot),
                ),
            ),
        )

        val rejected = assertInstanceOf<VerifiedAddFilePlanResult.Rejected>(
            backend.verifiedAddFileOperations(workspaceRoot)
                .plan(planRequest(workspaceRoot, target, "package demo\n\nclass AmbiguousOwner\n")),
        )

        assertEquals(VerifiedAddFileFailure.TARGET_AMBIGUOUSLY_OWNED, rejected.failure)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `cancellation after recovery preparation retains typed recovery authority`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = sourceRoot.resolve("CancelledCreate.kt")
        val content = "package demo\n\nclass CancelledCreate\n"
        val operations = backend(
            workspaceRoot,
            psiGeneration = { PsiModificationTracker.getInstance(project).modificationCount },
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = { throw ProcessCanceledException() },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        ).verifiedAddFileOperations(workspaceRoot)
        val planned = assertInstanceOf<VerifiedAddFilePlanResult.Planned>(
            operations.plan(planRequest(workspaceRoot, target, content)),
        )
        val outcome = operations.apply(applyRequest(workspaceRoot, planned))
        val recovery = assertInstanceOf<VerifiedAddFileApplyResult.RecoveryRequired>(
            outcome,
            "cancellation after preparation must retain exact recovery authority: $outcome",
        )
        assertEquals(VerifiedAddFileFailure.CANCELLED, recovery.failure)
        assertTrue(recovery.recoveryId.value.startsWith("af-"))
        assertTrue(Files.exists(target))
    }

    @Test
    fun `rejects an existing target without changing it`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val target = Path.of(sampleFile.virtualFile.path).toAbsolutePath().normalize()
        val before = Files.readAllBytes(target)

        val rejected = assertInstanceOf<VerifiedAddFilePlanResult.Rejected>(
            operations(workspaceRoot, sourceRoot)
                .plan(planRequest(workspaceRoot, target, "package demo\n\nclass Never\n")),
        )
        assertEquals(VerifiedAddFileFailure.TARGET_ALREADY_EXISTS, rejected.failure)
        assertTrue(Files.readAllBytes(target).contentEquals(before))
    }

    @Test
    fun `rejects a generated source root`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val generatedRoot = createSourceRoot(
            sourceRoot.resolve("generated/verified-add-file"),
            "GeneratedAnchor.kt",
            "package demo.generated\n\nclass GeneratedAnchor\n",
        )
        val target = generatedRoot.resolve("RejectedGenerated.kt")
        try {
            val backend = backend(
                workspaceRoot,
                workspaceModelReader = model(
                    workspaceRoot,
                    listOf(
                        association(
                            "generated",
                            workspaceRoot,
                            ":generated",
                            "main",
                            generatedGradleSourceRoot(generatedRoot),
                        ),
                    ),
                ),
            )
            val rejected = assertInstanceOf<VerifiedAddFilePlanResult.Rejected>(
                backend.verifiedAddFileOperations(workspaceRoot)
                    .plan(planRequest(workspaceRoot, target, "package demo.generated\n\nclass RejectedGenerated\n")),
            )
            assertEquals(VerifiedAddFileFailure.TARGET_GENERATED, rejected.failure)
            assertFalse(Files.exists(target))
        } finally {
            deleteSourceRoot(sourceRoot.resolve("generated"))
        }
    }

    @Test
    fun `rejects a target whose source-root child symlink escapes the workspace`() = runBlocking {
        ensureProjectReady()
        val sourceRoot = sourceRoot()
        val workspaceRoot = commonWorkspaceRoot(sourceRoot.toString(), sampleFile.virtualFile.path)
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val link = sourceRoot.resolve("verified-escape")
        Files.createSymbolicLink(link, outside)
        val target = link.resolve("Escaped.kt")
        try {
            val rejected = assertInstanceOf<VerifiedAddFilePlanResult.Rejected>(
                operations(workspaceRoot, sourceRoot)
                    .plan(planRequest(workspaceRoot, target, "package demo\n\nclass Escaped\n")),
            )
            assertEquals(VerifiedAddFileFailure.TARGET_SYMLINK_ESCAPE, rejected.failure)
            assertFalse(Files.exists(outside.resolve("Escaped.kt")))
        } finally {
            Files.deleteIfExists(link)
        }
    }

    private fun operations(workspaceRoot: Path, sourceRoot: Path): io.github.amichne.kast.server.change.NativeVerifiedAddFileOperations {
        val generation = AtomicLong(1L)
        return backend(
            workspaceRoot,
            psiGeneration = generation::get,
            workspaceTransitionRequester = TestWorkspaceTransitionRequester(
                onReconcile = {
                    testPublishedWorkspaceGeneration(
                        WorkspaceSemanticGeneration(generation.incrementAndGet()),
                    )
                },
            ),
            workspaceModelReader = model(workspaceRoot, sourceRoot),
        )
            .verifiedAddFileOperations(workspaceRoot)
    }

    private fun planRequest(
        workspaceRoot: Path,
        target: Path,
        content: String,
    ): VerifiedAddFilePlanRequest = VerifiedAddFilePlanRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        targetPath = refined(VerifiedAddFileTargetPath.refine(target.toString())),
        proposedContent = refined(VerifiedAddFileContent.refine(content)),
    )

    private fun applyRequest(
        workspaceRoot: Path,
        planned: VerifiedAddFilePlanResult.Planned,
        expectedVersion: Long = 0L,
        evidenceSha256: String? = null,
    ): VerifiedAddFileApplyRequest = VerifiedAddFileApplyRequest(
        workspaceRoot = NormalizedPath.ofAbsolute(workspaceRoot),
        planId = planned.planId,
        expectedVersion = refined(VerifiedAddFilePlanVersion.refine(expectedVersion)),
        approvalEvidence = VerifiedAddFileApprovalEvidence(
            approvedBy = refined(VerifiedAddFileApprovedBy.refine("kast-public-cli")),
            evidenceSha256 = refined(
                VerifiedAddFileApprovalEvidenceSha256.refine(
                    evidenceSha256 ?: FileHashing.sha256(
                        buildString {
                            append("kast-public-cli\n")
                            append("workspaceRoot=${workspaceRoot.toAbsolutePath().normalize()}\n")
                            append("planId=${planned.planId.value}\n")
                            append("expectedVersion=${planned.planVersion.value}\n")
                        },
                    ),
                ),
            ),
        )
    )

    private fun createSourceRoot(root: Path, fileName: String, content: String): Path {
        lateinit var createdRoot: Path
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val directory = VfsUtil.createDirectoryIfMissing(root.toString())
                    ?: error("Could not create generated source root")
                val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
                VfsUtil.saveText(file, content)
                createdRoot = Path.of(directory.path).toAbsolutePath().normalize()
            }
        }
        waitUntilIndexesAreReady(project)
        return createdRoot
    }

    private fun deleteSourceRoot(root: Path) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                LocalFileSystem.getInstance().findFileByNioFile(root)?.delete(this)
            }
        }
        waitUntilIndexesAreReady(project)
    }

    private fun <T> refined(refinement: VerifiedAddFileRefinement<T>): T =
        assertInstanceOf<VerifiedAddFileRefinement.Refined<T>>(refinement).value
}
