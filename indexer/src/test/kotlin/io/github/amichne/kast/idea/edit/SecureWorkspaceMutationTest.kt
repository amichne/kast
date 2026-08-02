package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.mutation.*

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SecureWorkspaceMutationTest {
    @Test
    fun `create preserves a concurrent final entry and never cleans it by name`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-concurrent-create").toRealPath()
        val target = workspaceRoot.resolve("Create.kt")
        val concurrent = "class ConcurrentCreate\n"

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeFinalCommit = { commitTarget, mutation ->
                    assertEquals(target, commitTarget)
                    assertEquals(IdeaWorkspaceMutation.CREATE_FILE, mutation)
                    Files.writeString(target, concurrent)
                },
            ).createFile(target, "class Created\n")
        }

        assertEquals(concurrent, Files.readString(target))
        assertEquals("CONFLICT", failure.errorCode)
    }

    @Test
    fun `replace maps a missing detached target to not found`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-missing-replace").toRealPath()
        val target = workspaceRoot.resolve("Missing.kt")

        val failure = assertThrows(NotFoundException::class.java) {
            SecureWorkspaceMutation(workspaceRoot).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256("missing"),
                content = "class Replacement\n",
            )
        }

        assertEquals("NOT_FOUND", failure.errorCode)
        assertEquals(target.toString(), failure.details["filePath"])
    }

    @Test
    fun `replace preserves a concurrent entry and quarantines the validated inode`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-concurrent-replace").toRealPath()
        val target = workspaceRoot.resolve("Replace.kt")
        val original = "class Original\n"
        val concurrent = "class Concurrent\n"
        val originalPermissions = PosixFilePermissions.fromString("rw-------")
        val concurrentPermissions = PosixFilePermissions.fromString("rw-r--r--")
        Files.writeString(target, original)
        Files.setPosixFilePermissions(target, originalPermissions)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                afterTargetDetached = { detachedTarget, mutation ->
                    assertEquals(target, detachedTarget)
                    assertEquals(IdeaWorkspaceMutation.TEXT_EDIT, mutation)
                    Files.writeString(target, concurrent)
                    Files.setPosixFilePermissions(target, concurrentPermissions)
                },
            ).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256(original),
                content = "class Replacement\n",
            )
        }

        assertEquals("CONFLICT", failure.errorCode)
        assertEquals("quarantined", failure.details["restoration"])
        assertEquals(concurrent, Files.readString(target))
        assertEquals(concurrentPermissions, Files.getPosixFilePermissions(target))
        val recoveryFile = Path.of(failure.details.getValue("recoveryFilePath"))
        assertTrue(Files.exists(recoveryFile), "The exact validated inode must remain recoverable")
        assertEquals(original, Files.readString(recoveryFile))
        assertEquals(originalPermissions, Files.getPosixFilePermissions(recoveryFile))
    }

    @Test
    fun `delete preserves both a concurrent entry and the validated inode when commit is blocked`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-concurrent-delete").toRealPath()
        val target = workspaceRoot.resolve("Delete.kt")
        val original = "class OriginalDelete\n"
        val concurrent = "class ConcurrentDelete\n"
        Files.writeString(target, original)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                afterTargetDetached = { detachedTarget, mutation ->
                    assertEquals(target, detachedTarget)
                    assertEquals(IdeaWorkspaceMutation.DELETE_FILE, mutation)
                    Files.writeString(target, concurrent)
                },
            ).deleteFile(
                target = target,
                expectedDiskHash = FileHashing.sha256(original),
            )
        }

        assertEquals("quarantined", failure.details["restoration"])
        assertEquals(concurrent, Files.readString(target))
        val recoveryFile = Path.of(failure.details.getValue("recoveryFilePath"))
        assertEquals(original, Files.readString(recoveryFile))
    }

    @Test
    fun `hash conflict restores the detached inode to its original name`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-hash-restore").toRealPath()
        val target = workspaceRoot.resolve("HashConflict.kt")
        val original = "class OriginalHash\n"
        Files.writeString(target, original)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(workspaceRoot).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256("stale"),
                content = "class Replacement\n",
            )
        }

        assertEquals("restored", failure.details["restoration"])
        assertEquals(original, Files.readString(target))
        Files.list(workspaceRoot).use { entries ->
            assertFalse(
                entries.anyMatch { entry -> entry.fileName.toString().startsWith(".kast-quarantine-") },
                "A successful rollback must not leave a quarantine entry",
            )
        }
    }

    @Test
    fun `replacement reports committed state when original cleanup is retained`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-retained-replace").toRealPath()
        val target = workspaceRoot.resolve("RetainedReplace.kt")
        val original = "class OriginalRetainedReplace\n"
        val replacement = "class ReplacementRetainedReplace\n"
        Files.writeString(target, original)
        var failNextCleanup = true

        val result = SecureWorkspaceMutation(
            workspaceRoot = workspaceRoot,
            beforeCleanupUnlink = {
                if (failNextCleanup) {
                    failNextCleanup = false
                    error("forced cleanup retention")
                }
            },
        ).replaceFile(target, FileHashing.sha256(original), replacement)

        assertTrue(result is SecureWorkspaceMutationResult.CommittedWithRecovery)
        val committed = result as SecureWorkspaceMutationResult.CommittedWithRecovery
        assertEquals(replacement, Files.readString(target))
        assertEquals(listOf(original), committed.recoveryFilePaths.map { path -> Files.readString(path) })
    }

    @Test
    fun `deletion reports committed state when original cleanup is retained`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-retained-delete").toRealPath()
        val target = workspaceRoot.resolve("RetainedDelete.kt")
        val original = "class OriginalRetainedDelete\n"
        Files.writeString(target, original)
        var cleanupCalls = 0

        val result = SecureWorkspaceMutation(
            workspaceRoot = workspaceRoot,
            beforeCleanupUnlink = {
                cleanupCalls += 1
                if (cleanupCalls == 2) {
                    error("forced cleanup retention")
                }
            },
        ).deleteFile(target, FileHashing.sha256(original))

        assertTrue(result is SecureWorkspaceMutationResult.CommittedWithRecovery)
        val committed = result as SecureWorkspaceMutationResult.CommittedWithRecovery
        assertFalse(Files.exists(target), "The validated original deletion must remain committed")
        assertEquals(listOf(original), committed.recoveryFilePaths.map { path -> Files.readString(path) })
    }

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
