package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.idea.mutation.*

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException

class SecureWorkspaceMutationCancellationTest {
    @Test
    fun `namespace conflict restores before fallible prepared cleanup and reports both recoveries`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-restore-order").toRealPath()
        val target = workspaceRoot.resolve("RestoreOrder.kt")
        val original = "class OriginalRestoreOrder\n"
        val concurrent = "class ConcurrentRestoreOrder\n"
        val replacement = "class ReplacementRestoreOrder\n"
        Files.writeString(target, original)
        var failNextCleanup = true

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                afterTargetDetached = { _, _ -> Files.writeString(target, concurrent) },
                beforeCleanupUnlink = {
                    if (failNextCleanup) {
                        failNextCleanup = false
                        error("forced prepared cleanup retention")
                    }
                },
            ).replaceFile(target, FileHashing.sha256(original), replacement)
        }

        assertEquals(concurrent, Files.readString(target))
        assertEquals(original, Files.readString(Path.of(failure.details.getValue("recoveryFilePath"))))
        assertEquals(
            replacement,
            Files.readString(Path.of(failure.details.getValue("cleanupRecoveryFilePath"))),
        )
    }

    @Test
    fun `preparation failure after detach restores the original final entry`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-preparation-rollback").toRealPath()
        val target = workspaceRoot.resolve("PreparationRollback.kt")
        val original = "class PreparationRollback\n"
        Files.writeString(target, original)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforePreparedFileCreation = { preparedTarget, mutation ->
                    assertEquals(target, preparedTarget)
                    assertEquals(IdeaWorkspaceMutation.TEXT_EDIT, mutation)
                    error("forced preparation failure")
                },
            ).replaceFile(target, FileHashing.sha256(original), "class Replacement\n")
        }

        assertEquals("restored", failure.details["restoration"])
        assertEquals(original, Files.readString(target))
    }

    @Test
    fun `replacement preserves task cancellation after restoring a detached target`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-replace-prepare-cancel").toRealPath()
        val target = workspaceRoot.resolve("ReplacePrepareCancellation.kt")
        val original = "class ReplacePrepareCancellation\n"
        val cancellation = CancellationException("cancel replacement preparation")
        Files.writeString(target, original)

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforePreparedFileCreation = { _, _ -> throw cancellation },
            ).replaceFile(target, FileHashing.sha256(original), "class Replaced\n")
        }

        assertSame(cancellation, thrown)
        assertEquals(original, Files.readString(target))
        val evidence = thrown.suppressed.filterIsInstance<ConflictException>().single()
        assertEquals("restored", evidence.details["restoration"])
    }

    @Test
    fun `replacement preserves IDEA cancellation after restoring and cleaning a prepared target`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-replace-commit-cancel").toRealPath()
        val target = workspaceRoot.resolve("ReplaceCommitCancellation.kt")
        val original = "class ReplaceCommitCancellation\n"
        val cancellation = ProcessCanceledException()
        Files.writeString(target, original)

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeFinalCommit = { _, mutation ->
                    if (mutation == IdeaWorkspaceMutation.TEXT_EDIT) throw cancellation
                },
            ).replaceFile(target, FileHashing.sha256(original), "class Replaced\n")
        }

        assertSame(cancellation, thrown)
        assertEquals(original, Files.readString(target))
        val evidence = thrown.suppressed.filterIsInstance<ConflictException>().single()
        assertEquals("restored", evidence.details["restoration"])
    }

    @Test
    fun `replacement rollback keeps the triggering cancellation primary when cleanup restoration cancels`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-replace-cleanup-restore-cancel").toRealPath()
        val target = workspaceRoot.resolve("ReplaceCleanupRestoreCancellation.kt")
        val original = "class ReplaceCleanupRestoreCancellation\n"
        val replacement = "class ReplacedCleanupRestoreCancellation\n"
        val primaryCancellation = CancellationException("cancel replacement commit")
        val laterCancellation = ProcessCanceledException()
        Files.writeString(target, original)

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeFinalCommit = { _, mutation ->
                    if (mutation == IdeaWorkspaceMutation.TEXT_EDIT) throw primaryCancellation
                },
                beforeCleanupUnlink = { error("force prepared cleanup retention") },
                beforeNoReplaceRename = { _, phase ->
                    if (phase == SecureWorkspaceRenamePhase.RESTORE_CLEANUP) throw laterCancellation
                },
            ).replaceFile(target, FileHashing.sha256(original), replacement)
        }

        assertSame(primaryCancellation, thrown)
        assertEquals(original, Files.readString(target))
        assertTrue(thrown.suppressed.isNotEmpty())
        assertTrue(
            thrown.suppressed.all { evidence ->
                evidence is ConflictException || evidence is UnsafeWorkspaceMutationException
            },
        )
        val evidence = thrown.suppressed.filterIsInstance<ConflictException>().single()
        val recoveryFile = Path.of(evidence.details.getValue("cleanupRecoveryFilePath"))
        assertEquals(replacement, Files.readString(recoveryFile))
    }

    @Test
    fun `native final commit failure restores original before prepared cleanup`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-native-commit-rollback").toRealPath()
        val target = workspaceRoot.resolve("NativeCommitRollback.kt")
        val original = "class NativeCommitRollback\n"
        val replacement = "class NativeCommitReplacement\n"
        Files.writeString(target, original)
        var cleanupObservedRestoredOriginal = false

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeNoReplaceRename = { renameTarget, phase ->
                    if (renameTarget == target && phase == SecureWorkspaceRenamePhase.FINAL_COMMIT) {
                        error("forced native final rename failure")
                    }
                },
                beforeCleanupUnlink = {
                    cleanupObservedRestoredOriginal = Files.readString(target) == original
                },
            ).replaceFile(target, FileHashing.sha256(original), replacement)
        }

        assertTrue(cleanupObservedRestoredOriginal, "Original restoration must precede prepared cleanup")
        assertEquals("restored", failure.details["restoration"])
        assertEquals(original, Files.readString(target))
    }

    @Test
    fun `late delete reservation replacement is restored and reported as conflict`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-late-delete-race").toRealPath()
        val target = workspaceRoot.resolve("LateDeleteRace.kt")
        val original = "class OriginalLateDelete\n"
        val concurrent = "class ConcurrentLateDelete\n"
        Files.writeString(target, original)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                afterDeleteReservationCommitted = { reservedTarget ->
                    assertEquals(target, reservedTarget)
                    Files.delete(reservedTarget)
                    Files.writeString(reservedTarget, concurrent)
                },
            ).deleteFile(target, FileHashing.sha256(original))
        }

        assertEquals(concurrent, Files.readString(target))
        assertEquals("restored", failure.details["concurrentEntryRestoration"])
        assertEquals(original, Files.readString(Path.of(failure.details.getValue("recoveryFilePath"))))
    }

    @Test
    fun `delete preserves IDEA cancellation after committing its reservation`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-delete-reserved-cancel").toRealPath()
        val target = workspaceRoot.resolve("DeleteReservedCancellation.kt")
        val original = "class DeleteReservedCancellation\n"
        val cancellation = ProcessCanceledException()
        Files.writeString(target, original)

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                afterDeleteReservationCommitted = { throw cancellation },
            ).deleteFile(target, FileHashing.sha256(original))
        }

        assertSame(cancellation, thrown)
        assertEquals(0L, Files.size(target), "The final name must retain the exact empty reservation")
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        assertEquals("2", evidence.details["recoveryFilePathCount"])
        val recoveryPaths = (0..1).map { index -> Path.of(evidence.details.getValue("recoveryFilePath.$index")) }
        assertTrue(recoveryPaths.contains(target))
        assertEquals(listOf(original), recoveryPaths.filterNot(target::equals).map(Files::readString))
    }

    @Test
    fun `non regular fifo target fails closed without hashing its stream`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-fifo").toRealPath()
        val target = workspaceRoot.resolve("Target.kt")
        val mkfifo = ProcessBuilder("mkfifo", target.toString()).start()
        assertEquals(0, mkfifo.waitFor(), "The test requires POSIX mkfifo")

        val failure = assertThrows(io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256(""),
                content = "class Replacement\n",
            )
        }

        assertEquals("reject-non-regular-target", failure.details["nativeOperation"])
        assertEquals("FIFO", failure.details["fileType"])
        assertTrue(Files.exists(target), "Rejected FIFO must be restored to its final name")
        assertFalse(Files.isRegularFile(target))
    }

    @Test
    fun `unopened quarantine restoration cancellation preserves cancellation identity and recovery evidence`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-fifo-restore-cancel").toRealPath()
        val target = workspaceRoot.resolve("Target.kt")
        val mkfifo = ProcessBuilder("mkfifo", target.toString()).start()
        val cancellation = CancellationException("cancel unopened quarantine restoration")
        assertEquals(0, mkfifo.waitFor(), "The test requires POSIX mkfifo")

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeNoReplaceRename = { _, phase ->
                    if (phase == SecureWorkspaceRenamePhase.RESTORE_TARGET) throw cancellation
                },
            ).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256(""),
                content = "class Replacement\n",
            )
        }

        assertSame(cancellation, thrown)
        assertFalse(Files.exists(target))
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        val recoveryFile = Path.of(evidence.details.getValue("recoveryFilePath"))
        assertTrue(Files.exists(recoveryFile), "The rejected FIFO must remain recoverable")
        assertFalse(Files.isRegularFile(recoveryFile))
    }

    @Test
    fun `post commit verification refuses an escaping final symlink`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-post-commit-verification").toRealPath()
        val target = workspaceRoot.resolve("Verified.kt")
        val committed = "class Verified\n"
        val outsideTarget = Files.createTempFile("kast-secure-outside-verification", ".kt")
        Files.writeString(outsideTarget, committed)
        val mutation = SecureWorkspaceMutation(workspaceRoot)
        mutation.createFile(target, committed)
        Files.delete(target)
        Files.createSymbolicLink(target, outsideTarget)

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            mutation.verifyCommittedFile(
                target = target,
                expectedContent = committed,
                mutation = IdeaWorkspaceMutation.CREATE_FILE,
            )
        }

        assertEquals("openat-verify-committed-file", failure.details["nativeOperation"])
        assertEquals(committed, Files.readString(outsideTarget))
    }
}
