package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.io.KastFileOperations
import io.github.amichne.kast.testing.inMemoryFileOperations

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * RED test proving atomic write semantics must be preserved through injection.
 *
 * This test uses a recording KastFileOperations fake that detects whether
 * LocalDiskEditApplier uses atomic temp-file-and-move operations rather than
 * direct writeText to existing files.
 *
 * Expected failure mode with current code:
 * - Test fails because LocalDiskEditApplier calls fileOps.writeText(existingFile)
 *   directly instead of using a temp-file-and-move pattern for atomic writes.
 */
class AtomicWriteRegressionTest {

    /**
     * Recording fake that tracks operations to detect atomic write pattern.
     * Fails if writeText is called on an existing file without using temp-file pattern.
     */
    private class RecordingFileOps(private val delegate: KastFileOperations) : KastFileOperations {
        val operations = mutableListOf<String>()
        private val createdFiles = mutableSetOf<String>()
        private val tempFiles = mutableSetOf<String>()

        override fun readText(path: String): String {
            operations += "readText($path)"
            return delegate.readText(path)
        }

        override fun writeText(path: String, content: String) {
            operations += "writeText($path)"

            // Detect non-atomic write to existing file
            // (atomic pattern would use temp file, not direct write)
            if (delegate.exists(path) && !createdFiles.contains(path) && !tempFiles.contains(path)) {
                throw AssertionError(
                    "Non-atomic write detected: writeText called on existing file $path. " +
                    "Expected temp-file-and-move atomic pattern."
                )
            }

            createdFiles += path
            delegate.writeText(path, content)
        }

        override fun exists(path: String): Boolean {
            operations += "exists($path)"
            return delegate.exists(path)
        }

        override fun list(path: String): List<String> {
            operations += "list($path)"
            return delegate.list(path)
        }

        override fun delete(path: String): Boolean {
            operations += "delete($path)"
            return delegate.delete(path)
        }

        override fun createTempFile(targetPath: String): String {
            val tempPath = delegate.createTempFile(targetPath)
            operations += "createTempFile($targetPath) -> $tempPath"
            tempFiles += tempPath
            return tempPath
        }

        override fun moveAtomic(sourcePath: String, destPath: String) {
            operations += "moveAtomic($sourcePath, $destPath)"
            delegate.moveAtomic(sourcePath, destPath)
        }

        override fun <T> withLock(path: String, block: () -> T): T {
            operations += "withLock($path)"
            return delegate.withLock(path, block)
        }
    }

    @Test
    fun `edit to existing file must use atomic write pattern not direct writeText`() {
        // Arrange: Create existing file in memory
        val fixture = inMemoryFileOperations()
        val testFile = "${fixture.root}test/EditMe.kt"
        val originalContent = "class EditMe {}"
        fixture.createFile(testFile, originalContent)

        // Recording wrapper to detect atomic pattern
        val recording = RecordingFileOps(fixture.fileOps)

        val insertOffset = originalContent.indexOf('{') + 1

        // Act: Apply text edit - should use atomic temp-file-and-move
        val applier = LocalDiskEditApplier(recording)
        val result = applier.apply(
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = testFile,
                        startOffset = insertOffset,
                        endOffset = insertOffset,
                        newText = "\n    val x = 42\n",
                    ),
                ),
                fileHashes = listOf(
                    FileHash(
                        filePath = testFile,
                        hash = FileHashing.sha256(originalContent),
                    ),
                ),
                fileOperations = emptyList(),
            ),
        )

        // Assert: Should succeed using atomic pattern
        val expectedContent = "class EditMe {\n    val x = 42\n}"
        assertEquals(expectedContent, fixture.fileOps.readText(testFile), "Content should be updated")
        assertEquals(listOf(testFile), result.affectedFiles, "Result should list affected file")

        // Verify atomic operations were used
        assertTrue(
            recording.operations.any { it.contains("createTempFile") },
            "Should create temp file for atomic write"
        )
        assertTrue(
            recording.operations.any { it.contains("moveAtomic") },
            "Should use atomic move for crash safety"
        )
    }

    @Test
    fun `create file operation does not require atomic write`() {
        // Arrange: Empty in-memory filesystem
        val fixture = inMemoryFileOperations()
        val testFile = "${fixture.root}test/NewFile.kt"
        val content = "class NewFile\n"

        // Recording wrapper
        val recording = RecordingFileOps(fixture.fileOps)

        // Act: CreateFile operation - direct writeText is acceptable for new files
        val applier = LocalDiskEditApplier(recording)
        val result = applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = testFile,
                        content = content,
                    ),
                ),
            ),
        )

        // Assert: Should succeed - new file creation doesn't need atomic semantics
        assertEquals(listOf(testFile), result.createdFiles)
        assertTrue(recording.operations.any { it.contains("writeText") })
    }
}
