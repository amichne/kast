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

class SecureWorkspaceMutationCreateTest {
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
}
