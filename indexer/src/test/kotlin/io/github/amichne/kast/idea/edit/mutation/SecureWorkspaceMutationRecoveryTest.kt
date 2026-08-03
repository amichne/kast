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

class SecureWorkspaceMutationRecoveryTest {
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
    fun `hash conflict restoration cancellation preserves cancellation identity and recovery evidence`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-hash-restore-cancel").toRealPath()
        val target = workspaceRoot.resolve("HashConflictCancellation.kt")
        val original = "class OriginalHashCancellation\n"
        val cancellation = ProcessCanceledException()
        Files.writeString(target, original)

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeNoReplaceRename = { _, phase ->
                    if (phase == SecureWorkspaceRenamePhase.RESTORE_TARGET) throw cancellation
                },
            ).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256("stale"),
                content = "class Replacement\n",
            )
        }

        assertSame(cancellation, thrown)
        assertFalse(Files.exists(target))
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        val recoveryFile = Path.of(evidence.details.getValue("recoveryFilePath"))
        assertEquals(original, Files.readString(recoveryFile))
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
    fun `deletion reservation cleanup preserves cancellation identity and evidence`() {
        val workspaceRoot = Files.createTempDirectory("kast-delete-reservation-cancel").toRealPath()
        val target = workspaceRoot.resolve("ReservationCancellation.kt")
        val original = "class ReservationCancellation\n"
        Files.writeString(target, original)
        val cancellation = CancellationException("cancel reservation cleanup")
        var cleanupCalls = 0

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeCleanupUnlink = {
                    cleanupCalls += 1
                    if (cleanupCalls == 1) throw cancellation
                },
            ).deleteFile(target, FileHashing.sha256(original))
        }

        assertSame(cancellation, thrown)
        assertFalse(Files.exists(target))
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        assertEquals("1", evidence.details["recoveryFilePathCount"])
        val retained = Path.of(evidence.details.getValue("recoveryFilePath.0"))
        assertTrue(Files.exists(retained))
        assertEquals(0L, Files.size(retained), "The retained reservation must remain an exact empty file")
    }

    @Test
    fun `deletion quarantine cleanup preserves cancellation identity and evidence`() {
        val workspaceRoot = Files.createTempDirectory("kast-delete-quarantine-cancel").toRealPath()
        val target = workspaceRoot.resolve("QuarantineCancellation.kt")
        val original = "class QuarantineCancellation\n"
        Files.writeString(target, original)
        val cancellation = CancellationException("cancel quarantine cleanup")
        var cleanupCalls = 0

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeCleanupUnlink = {
                    cleanupCalls += 1
                    if (cleanupCalls == 2) throw cancellation
                },
            ).deleteFile(target, FileHashing.sha256(original))
        }

        assertSame(cancellation, thrown)
        assertFalse(Files.exists(target))
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        assertEquals("1", evidence.details["recoveryFilePathCount"])
        val retained = Path.of(evidence.details.getValue("recoveryFilePath.0"))
        assertEquals(original, Files.readString(retained))
    }

    @Test
    fun `deletion with two cleanup cancellations suppresses only typed recovery evidence`() {
        val workspaceRoot = Files.createTempDirectory("kast-delete-double-cleanup-cancel").toRealPath()
        val target = workspaceRoot.resolve("DoubleCleanupCancellation.kt")
        val original = "class DoubleCleanupCancellation\n"
        val primaryCancellation = CancellationException("cancel reservation cleanup")
        val laterCancellation = ProcessCanceledException()
        var cleanupCalls = 0
        Files.writeString(target, original)

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeCleanupUnlink = {
                    cleanupCalls += 1
                    if (cleanupCalls == 1) throw primaryCancellation
                    throw laterCancellation
                },
            ).deleteFile(target, FileHashing.sha256(original))
        }

        assertSame(primaryCancellation, thrown)
        assertTrue(thrown.suppressed.isNotEmpty())
        assertTrue(thrown.suppressed.all { evidence -> evidence is UnsafeWorkspaceMutationException })
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        assertEquals("2", evidence.details["recoveryFilePathCount"])
        assertFalse(Files.exists(target))
    }
}
