package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.testing.inMemoryFileOperations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RED test to prove LocalDiskEditApplier needs KastFileOperations injection.
 *
 * This test exercises LocalDiskEditApplier with Jimfs-backed file operations.
 * It should fail because LocalDiskEditApplier is currently an object that
 * directly uses Path.of() and Files.*, with no way to inject alternative
 * file operations.
 *
 * Expected failure mode:
 * - Test will compile but fail at runtime because LocalDiskEditApplier
 *   cannot operate on Jimfs paths (they exist only in-memory, not on disk)
 */
class LocalDiskEditApplierInjectionTest {

    @Test
    fun `apply CreateFile should work with injected Jimfs fileOps`() {
        // Arrange: Create in-memory filesystem
        val fixture = inMemoryFileOperations()
        val testFile = "${fixture.root}test/NewFile.kt"
        val expectedContent = "class NewFile\n"

        // Act: Apply CreateFile operation via LocalDiskEditApplier with injected fileOps
        // GREEN: LocalDiskEditApplier now accepts fileOps parameter
        val applier = LocalDiskEditApplier(fixture.fileOps)
        val result = applier.apply(
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        filePath = testFile,
                        content = expectedContent,
                    ),
                ),
            ),
        )

        // Assert: File should exist in Jimfs and match content
        assertTrue(fixture.fileOps.exists(testFile), "File should exist in Jimfs")
        assertEquals(expectedContent, fixture.fileOps.readText(testFile), "Content should match")
        assertEquals(listOf(testFile), result.createdFiles, "Result should list created file")
    }

    @Test
    fun `apply TextEdit should work with injected Jimfs fileOps`() {
        // Arrange: Create file in Jimfs
        val fixture = inMemoryFileOperations()
        val testFile = "${fixture.root}test/EditMe.kt"
        val originalContent = "class EditMe {}"
        fixture.createFile(testFile, originalContent)

        val insertOffset = originalContent.indexOf('{') + 1

        // Act: Apply text edit via LocalDiskEditApplier with injected fileOps
        // GREEN: LocalDiskEditApplier now accepts fileOps parameter
        val applier = LocalDiskEditApplier(fixture.fileOps)
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

        // Assert: File should be updated in Jimfs
        val expectedContent = "class EditMe {\n    val x = 42\n}"
        assertEquals(expectedContent, fixture.fileOps.readText(testFile), "Content should be updated")
        assertEquals(listOf(testFile), result.affectedFiles, "Result should list affected file")
    }
}
