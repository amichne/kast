package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.impl.ZipHandler
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals

/** Platform archive behavior only. This case does not establish compiler reads or native snapshot coherence. */
class NativeHistoricalArchiveInputTest : NativeSmartModeFixtureTest() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("kast-historical-archive-")
    }

    override fun tearDown() {
        try {
            Files.deleteIfExists(root.resolve("candidate.jar"))
            Files.deleteIfExists(root.resolve("resolution.jar"))
            Files.delete(root)
        } finally {
            super.tearDown()
        }
    }

    fun testEqualEndpointDigestsPermitDifferentArchiveBytesInsideOneReadAction() {
        val original = archive("version A")
        val changed = archive("version B")
        val path = replace(original, ORIGINAL_TIME)
        val handler = ZipHandler(path.toString())
        try {
            ReadAction.run<RuntimeException> {
                assertTrue(ApplicationManager.getApplication().isReadAccessAllowed)
                assertFalse(ApplicationManager.getApplication().isWriteAccessAllowed)
                val before = digest(path)
                assertEquals("version A", handler.contentsToByteArray(ENTRY).decodeToString())

                // Atomic replacement avoids mutation of an open ZIP handle. No VFS refresh or IDE write occurs.
                replace(changed, CHANGED_TIME)
                assertEquals("version B", handler.contentsToByteArray(ENTRY).decodeToString())

                replace(original, ORIGINAL_TIME)
                assertEquals("version A", handler.contentsToByteArray(ENTRY).decodeToString())
                assertArrayEquals(before, digest(path))
                assertArrayEquals(original, Files.readAllBytes(path))
            }
        } finally {
            handler.clearCaches()
        }
    }

    fun testUnchangedArchiveReturnsTheSameBytesInsideOneReadAction() {
        val path = replace(archive("version A"), ORIGINAL_TIME)
        val handler = ZipHandler(path.toString())
        try {
            ReadAction.run<RuntimeException> {
                val before = digest(path)
                repeat(READ_COUNT) { assertEquals("version A", handler.contentsToByteArray(ENTRY).decodeToString()) }
                assertArrayEquals(before, digest(path))
            }
        } finally {
            handler.clearCaches()
        }
    }

    private fun replace(bytes: ByteArray, timestamp: Long): Path {
        val candidate = root.resolve("candidate.jar")
        val destination = root.resolve("resolution.jar")
        Files.write(candidate, bytes)
        Files.setLastModifiedTime(candidate, FileTime.fromMillis(timestamp))
        Files.move(candidate, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return destination
    }

    private fun archive(content: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY).apply { time = ORIGINAL_TIME })
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            bytes.toByteArray()
        }

    private fun digest(path: Path): ByteArray = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))

    private companion object {
        const val READ_COUNT = 3
        const val ENTRY = "resolution.txt"
        const val ORIGINAL_TIME = 1700000000000L
        const val CHANGED_TIME = 1700000010000L
    }
}
