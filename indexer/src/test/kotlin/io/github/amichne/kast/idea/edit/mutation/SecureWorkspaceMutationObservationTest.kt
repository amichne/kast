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

class SecureWorkspaceMutationObservationTest {
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
}
