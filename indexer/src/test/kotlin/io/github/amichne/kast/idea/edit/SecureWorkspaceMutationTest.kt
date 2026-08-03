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

class SecureWorkspaceMutationTest {
    @Test
    fun `byte replacement read hash and verification retain the exact image`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-byte-image").toRealPath()
        val target = workspaceRoot.resolve("Bytes.kt")
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "old\r\n".toByteArray()
        val replacement = byteArrayOf(0x00, 0x7f, 0xff.toByte(), 0x0d, 0x0a)
        Files.write(target, original)
        val mutation = SecureWorkspaceMutation(workspaceRoot)

        assertArrayEquals(original, mutation.readFileBytes(target, IdeaWorkspaceMutation.TEXT_EDIT))
        assertEquals(FileHashing.sha256(original), mutation.currentFileSha256(target, IdeaWorkspaceMutation.TEXT_EDIT))

        mutation.replaceFile(target, FileHashing.sha256(original), replacement)
        mutation.verifyCommittedFile(target, replacement, IdeaWorkspaceMutation.TEXT_EDIT)

        assertArrayEquals(replacement, Files.readAllBytes(target))
        assertEquals(FileHashing.sha256(replacement), mutation.currentFileSha256(target, IdeaWorkspaceMutation.TEXT_EDIT))
    }

    @Test
    fun `exact observation returns immutable BOM CRLF non-BMP and arbitrary bytes without writing`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-present").toRealPath()
        val target = workspaceRoot.resolve("Observed.kt")
        val exactContent = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            "val face = \"😀\"\r\n".toByteArray() +
            byteArrayOf(0x00, 0x7f, 0xff.toByte())
        Files.write(target, exactContent)
        val modifiedBefore = Files.getLastModifiedTime(target)

        val observation = SecureWorkspaceMutation(workspaceRoot)
            .observeExactFile(target, IdeaWorkspaceMutation.CREATE_FILE)
        val present = observation as SecureWorkspaceFileObservation.Present

        assertArrayEquals(exactContent, present.bytes)
        assertEquals(FileHashing.sha256(exactContent), present.sha256)
        assertTrue(present.sha256.matches(Regex("[0-9a-f]{64}")), "The observation hash must be lowercase SHA-256")
        assertArrayEquals(exactContent, Files.readAllBytes(target))
        assertEquals(modifiedBefore, Files.getLastModifiedTime(target), "Observation must not write the file")
    }

    @Test
    fun `exact observation returns absent only for a missing final entry under an existing parent`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-absent").toRealPath()
        val parent = Files.createDirectory(workspaceRoot.resolve("existing"))
        val target = parent.resolve("Absent.kt")
        val parentModifiedBefore = Files.getLastModifiedTime(parent)

        val observation = SecureWorkspaceMutation(workspaceRoot)
            .observeExactFile(target, IdeaWorkspaceMutation.CREATE_FILE)

        assertEquals(SecureWorkspaceFileObservation.Absent, observation)
        assertTrue(Files.notExists(target), "Observation must not create the absent final entry")
        assertEquals(parentModifiedBefore, Files.getLastModifiedTime(parent), "Observation must not write the parent")
    }

    @Test
    fun `exact observation rejects a missing parent instead of reporting absent`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-missing-parent").toRealPath()
        val missingParent = workspaceRoot.resolve("missing")
        val target = missingParent.resolve("Absent.kt")

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .observeExactFile(target, IdeaWorkspaceMutation.CREATE_FILE)
        }

        assertEquals("openat-directory", failure.details["nativeOperation"])
        assertTrue(Files.notExists(missingParent), "Observation must not create a missing parent")
    }

    @Test
    fun `exact observation rejects a replaced directory parent instead of reporting absent`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-replaced-parent").toRealPath()
        val replacedParent = workspaceRoot.resolve("replaced")
        val replacementContent = byteArrayOf(0x01, 0x02, 0x03)
        Files.write(replacedParent, replacementContent)

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .observeExactFile(replacedParent.resolve("Absent.kt"), IdeaWorkspaceMutation.CREATE_FILE)
        }

        assertEquals("openat-directory", failure.details["nativeOperation"])
        assertArrayEquals(replacementContent, Files.readAllBytes(replacedParent))
    }

    @Test
    fun `exact observation rejects an escaping symlink parent and target`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-links").toRealPath()
        val outsideParent = Files.createTempDirectory("kast-secure-observe-links-outside").toRealPath()
        val linkedParent = workspaceRoot.resolve("linked-parent")
        Files.createSymbolicLink(linkedParent, outsideParent)
        val outsideTarget = Files.createTempFile("kast-secure-observe-linked-target", ".kt").toRealPath()
        val outsideContent = byteArrayOf(0x04, 0x05, 0x06)
        Files.write(outsideTarget, outsideContent)
        val linkedTarget = workspaceRoot.resolve("linked-target.kt")
        Files.createSymbolicLink(linkedTarget, outsideTarget)
        val mutation = SecureWorkspaceMutation(workspaceRoot)

        val parentFailure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            mutation.observeExactFile(linkedParent.resolve("Absent.kt"), IdeaWorkspaceMutation.CREATE_FILE)
        }
        val targetFailure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            mutation.observeExactFile(linkedTarget, IdeaWorkspaceMutation.CREATE_FILE)
        }

        assertEquals("openat-directory", parentFailure.details["nativeOperation"])
        assertEquals("openat-observe-exact-file", targetFailure.details["nativeOperation"])
        assertTrue(Files.isSymbolicLink(linkedParent))
        assertTrue(Files.isSymbolicLink(linkedTarget))
        assertArrayEquals(outsideContent, Files.readAllBytes(outsideTarget))
        assertTrue(Files.notExists(outsideParent.resolve("Absent.kt")))
    }

    @Test
    fun `exact observation rejects a non-regular final entry`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-fifo").toRealPath()
        val target = workspaceRoot.resolve("Observed.pipe")
        val mkfifo = ProcessBuilder("mkfifo", target.toString()).start()
        assertEquals(0, mkfifo.waitFor(), "The test requires POSIX mkfifo")

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .observeExactFile(target, IdeaWorkspaceMutation.CREATE_FILE)
        }

        assertEquals("reject-non-regular-observation-target", failure.details["nativeOperation"])
        assertEquals("FIFO", failure.details["fileType"])
        assertTrue(Files.exists(target))
        assertFalse(Files.isRegularFile(target))
    }

    @Test
    fun `exact observation present bytes are defensive copies`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-copy").toRealPath()
        val target = workspaceRoot.resolve("Defensive.bin")
        val exactContent = byteArrayOf(0x10, 0x20, 0x30)
        Files.write(target, exactContent)

        val present = SecureWorkspaceMutation(workspaceRoot)
            .observeExactFile(target, IdeaWorkspaceMutation.CREATE_FILE) as SecureWorkspaceFileObservation.Present
        val firstRead = present.bytes
        firstRead.fill(0)

        assertArrayEquals(exactContent, present.bytes)
        assertEquals(FileHashing.sha256(exactContent), present.sha256)
        assertArrayEquals(exactContent, Files.readAllBytes(target))
    }

    @Test
    fun `exact observation rejects an outside workspace target`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-observe-root").toRealPath()
        val outsideTarget = Files.createTempFile("kast-secure-observe-outside", ".kt").toRealPath()
        val outsideContent = byteArrayOf(0x21, 0x22)
        Files.write(outsideTarget, outsideContent)

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .observeExactFile(outsideTarget, IdeaWorkspaceMutation.CREATE_FILE)
        }

        assertEquals("create_file", failure.details["nativeOperation"])
        assertArrayEquals(outsideContent, Files.readAllBytes(outsideTarget))
    }

    @Test
    fun `string replacement rejects malformed UTF-16 before detaching the target`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-strict-utf8").toRealPath()
        val target = workspaceRoot.resolve("Strict.kt")
        val original = "class Original\n"
        Files.writeString(target, original)

        assertThrows(IllegalArgumentException::class.java) {
            SecureWorkspaceMutation(workspaceRoot).replaceFile(
                target = target,
                expectedDiskHash = FileHashing.sha256(original),
                content = "\uD83D",
            )
        }

        assertEquals(original, Files.readString(target))
    }

    @Test
    fun `existing parent byte create writes the exact image`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-existing-parent-bytes").toRealPath()
        val parent = Files.createDirectories(workspaceRoot.resolve("existing/source"))
        val target = parent.resolve("Exact.bin")
        val exactContent = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0x00, 0x7f, 0xff.toByte())

        val result = SecureWorkspaceMutation(workspaceRoot)
            .createFileRequiringExistingParents(target, exactContent)

        assertEquals(SecureWorkspaceMutationResult.Committed, result)
        assertArrayEquals(exactContent, Files.readAllBytes(target))
    }

    @Test
    fun `existing parent create rejects a missing parent without writing`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-existing-parent-missing").toRealPath()
        val missingRoot = workspaceRoot.resolve("missing")
        val target = missingRoot.resolve("source/Missing.kt")

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .createFileRequiringExistingParents(target, "class Missing\n")
        }

        assertEquals("openat-directory", failure.details["nativeOperation"])
        assertTrue(Files.notExists(missingRoot), "A rejected create must not materialize any parent")
        assertTrue(Files.notExists(target), "A rejected create must not write the target")
    }

    @Test
    fun `existing parent create rejects an escaping symlink parent without writing`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-existing-parent-link").toRealPath()
        val outsideParent = Files.createTempDirectory("kast-secure-existing-parent-outside").toRealPath()
        val linkedParent = workspaceRoot.resolve("linked")
        Files.createSymbolicLink(linkedParent, outsideParent)
        val target = linkedParent.resolve("Escaped.kt")

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .createFileRequiringExistingParents(target, "class Escaped\n")
        }

        assertEquals("openat-directory", failure.details["nativeOperation"])
        assertTrue(Files.isSymbolicLink(linkedParent), "The rejected parent link must remain intact")
        assertTrue(Files.notExists(outsideParent.resolve(target.fileName)), "No file may escape through the link")
    }

    @Test
    fun `existing parent create preserves an escaping symlink target on conflict`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-existing-target-link").toRealPath()
        val parent = Files.createDirectory(workspaceRoot.resolve("existing"))
        val outsideTarget = Files.createTempFile("kast-secure-existing-target-outside", ".kt").toRealPath()
        val outsideContent = byteArrayOf(0x00, 0x01, 0x7f)
        Files.write(outsideTarget, outsideContent)
        val target = parent.resolve("Linked.kt")
        Files.createSymbolicLink(target, outsideTarget)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .createFileRequiringExistingParents(target, byteArrayOf(0x02, 0x03))
        }

        assertEquals("CONFLICT", failure.errorCode)
        assertTrue(Files.isSymbolicLink(target), "The conflicting target link must remain intact")
        assertArrayEquals(outsideContent, Files.readAllBytes(outsideTarget))
    }

    @Test
    fun `existing parent create preserves an existing target on conflict`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-existing-target").toRealPath()
        val target = workspaceRoot.resolve("Existing.kt")
        val existingContent = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0x0a)
        Files.write(target, existingContent)

        val failure = assertThrows(ConflictException::class.java) {
            SecureWorkspaceMutation(workspaceRoot)
                .createFileRequiringExistingParents(target, byteArrayOf(0x01, 0x02))
        }

        assertEquals("CONFLICT", failure.errorCode)
        assertArrayEquals(existingContent, Files.readAllBytes(target))
    }

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
    fun `create preserves IDEA cancellation raised before final commit`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-create-cancel").toRealPath()
        val target = workspaceRoot.resolve("CreateCancellation.kt")
        val cancellation = ProcessCanceledException()

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeFinalCommit = { _, _ -> throw cancellation },
            ).createFileRequiringExistingParents(target, "class CreateCancellation\n")
        }

        assertSame(cancellation, thrown)
        assertFalse(Files.exists(target))
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        assertEquals("create-before-commit", evidence.details["nativeOperation"])
    }

    @Test
    fun `create preserves cleanup cancellation when a concurrent target wins`() {
        val workspaceRoot = Files.createTempDirectory("kast-secure-create-cleanup-cancel").toRealPath()
        val target = workspaceRoot.resolve("CreateCleanupCancellation.kt")
        val concurrent = "class ConcurrentCreateCleanupCancellation\n"
        val cancellation = CancellationException("cancel create cleanup")

        val thrown = assertThrows(CancellationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = workspaceRoot,
                beforeFinalCommit = { _, _ -> Files.writeString(target, concurrent) },
                beforeCleanupUnlink = { throw cancellation },
            ).createFileRequiringExistingParents(target, "class CreateCleanupCancellation\n")
        }

        assertSame(cancellation, thrown)
        assertEquals(concurrent, Files.readString(target))
        assertTrue(thrown.suppressed.single() is ConflictException)
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
