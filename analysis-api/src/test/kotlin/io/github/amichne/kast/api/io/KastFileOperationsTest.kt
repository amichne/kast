package io.github.amichne.kast.api.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/**
 * RED phase tracer bullet test for KastFileOperations.
 *
 * This test defines the desired behavior for the LocalDisk-backed implementation.
 * Expected to FAIL during RED phase because LocalDiskFileOperations intentionally
 * throws NotImplementedError.
 */
class KastFileOperationsTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Tracer bullet test exercising the full lifecycle of file operations
     * through the KastFileOperations abstraction.
     *
     * This test validates:
     * - writeText creates files with content
     * - readText retrieves the written content
     * - exists reports file existence correctly
     * - list enumerates directory children
     * - delete removes files
     */
    @Test
    fun `local disk file operations read write list and delete through abstraction`() {
        val ops: KastFileOperations = LocalDiskFileOperations

        // Setup: use temp directory for isolated testing
        val testFile = tempDir.resolve("test.txt").toString()
        val testDir = tempDir.toString()

        // Verify file doesn't exist initially
        assertFalse(ops.exists(testFile), "File should not exist before creation")

        // Write content to file
        val content = "Hello, Kast filesystem abstraction!"
        ops.writeText(testFile, content)

        // Verify file was created
        assertTrue(ops.exists(testFile), "File should exist after writeText")

        // Read content back
        val readContent = ops.readText(testFile)
        assertEquals(content, readContent, "Read content should match written content")

        // List directory contents
        val children = ops.list(testDir)
        assertTrue(children.any { it.endsWith("test.txt") },
            "Directory listing should include test.txt")

        // Delete file
        val deleted = ops.delete(testFile)
        assertTrue(deleted, "delete should return true for existing file")

        // Verify file no longer exists
        assertFalse(ops.exists(testFile), "File should not exist after deletion")

        // Verify delete returns false for non-existent file
        val deletedAgain = ops.delete(testFile)
        assertFalse(deletedAgain, "delete should return false for non-existent file")
    }

    @Test
    fun `readText throws FileNotFoundException for non-existent file`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val nonExistentPath = tempDir.resolve("does-not-exist.txt").toString()

        assertThrows<FileNotFoundException> {
            ops.readText(nonExistentPath)
        }
    }

    @Test
    fun `writeText overwrites existing file content`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val testFile = tempDir.resolve("overwrite-test.txt").toString()

        // Write initial content
        ops.writeText(testFile, "initial content")

        // Overwrite with new content
        val newContent = "new content"
        ops.writeText(testFile, newContent)

        // Verify only new content remains
        val readContent = ops.readText(testFile)
        assertEquals(newContent, readContent, "File should contain only new content after overwrite")
    }

    @Test
    fun `list returns empty list for empty directory`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val emptyDir = tempDir.resolve("empty").toString()

        // Create empty directory using Java NIO (setup only)
        Files.createDirectory(Path.of(emptyDir))

        val children = ops.list(emptyDir)
        assertTrue(children.isEmpty(), "Empty directory should have no children")
    }

    @Test
    fun `exists returns false for non-existent path`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val nonExistentPath = tempDir.resolve("does-not-exist").toString()

        assertFalse(ops.exists(nonExistentPath), "Non-existent path should return false")
    }

    /**
     * RED test: Verify that writeText creates parent directories automatically.
     * This ensures consistency with JimfsFileOperations and matches the documented contract.
     *
     * Expected to FAIL initially if LocalDiskFileOperations doesn't create parents.
     */
    @Test
    fun `writeText creates parent directories if they do not exist`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val nestedFile = tempDir.resolve("deeply/nested/path/test.txt").toString()

        // Verify parent directories don't exist initially
        val parentDir = tempDir.resolve("deeply/nested/path").toString()
        assertFalse(ops.exists(parentDir), "Parent directories should not exist before writeText")

        // Write to file in non-existent directory structure
        val content = "Content in nested location"
        ops.writeText(nestedFile, content)

        // Verify file was created and parent directories exist
        assertTrue(ops.exists(nestedFile), "File should exist after writeText")
        assertTrue(ops.exists(parentDir), "Parent directories should be created")

        // Verify content is correct
        val readContent = ops.readText(nestedFile)
        assertEquals(content, readContent, "Content should match what was written")
    }

    /**
     * RED test: Verify that list() returns absolute paths, not relative.
     * This ensures the contract is unambiguous across implementations.
     *
     * Expected to FAIL or require verification that current behavior matches contract.
     */
    @Test
    fun `list returns absolute paths`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val testDir = tempDir.toString()
        val testFile = tempDir.resolve("absolute-test.txt").toString()

        // Create a file in the test directory
        ops.writeText(testFile, "test content")

        // List directory contents
        val children = ops.list(testDir)

        // Verify all returned paths are absolute
        assertTrue(children.isNotEmpty(), "Directory should contain at least one file")
        children.forEach { path ->
            assertTrue(Path.of(path).isAbsolute,
                "Path should be absolute, but got: $path")
        }
    }

    /**
     * Test atomic write operations: createTempFile + writeText + moveAtomic.
     *
     * This validates the atomic write pattern used for crash safety:
     * 1. Create temp file in same directory as target
     * 2. Write content to temp file
     * 3. Atomically move temp file to target location
     */
    @Test
    fun `createTempFile and moveAtomic provide atomic write semantics`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val targetFile = tempDir.resolve("target.txt").toString()
        val content = "Atomic content"

        // Create temp file for atomic write
        val tempFile = ops.createTempFile(targetFile)

        // Verify temp file is in same directory as target
        val tempPath = Path.of(tempFile)
        val targetPath = Path.of(targetFile)
        assertEquals(
            targetPath.parent,
            tempPath.parent,
            "Temp file should be in same directory as target for atomic move"
        )

        // Verify temp file exists after creation
        assertTrue(ops.exists(tempFile), "Temp file should exist after creation")

        // Write content to temp file
        ops.writeText(tempFile, content)

        // Move temp file atomically to target
        ops.moveAtomic(tempFile, targetFile)

        // Verify target file exists with correct content
        assertTrue(ops.exists(targetFile), "Target file should exist after atomic move")
        assertEquals(content, ops.readText(targetFile), "Target should have temp file content")

        // Verify temp file no longer exists
        assertFalse(ops.exists(tempFile), "Temp file should not exist after move")
    }

    /**
     * Test that moveAtomic replaces existing file atomically.
     */
    @Test
    fun `moveAtomic replaces existing file atomically`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val targetFile = tempDir.resolve("replace-target.txt").toString()

        // Create existing target with initial content
        val initialContent = "initial content"
        ops.writeText(targetFile, initialContent)

        // Create temp file with new content
        val tempFile = ops.createTempFile(targetFile)
        val newContent = "new content"
        ops.writeText(tempFile, newContent)

        // Atomically replace target with temp file content
        ops.moveAtomic(tempFile, targetFile)

        // Verify target has new content
        assertEquals(newContent, ops.readText(targetFile), "Target should have new content after atomic move")
        assertFalse(ops.exists(tempFile), "Temp file should not exist after move")
    }

    /**
     * Test that createTempFile creates parent directories if needed.
     */
    @Test
    fun `createTempFile creates parent directories automatically`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val nestedTarget = tempDir.resolve("nested/dir/target.txt").toString()

        // Create temp file for nested target
        val tempFile = ops.createTempFile(nestedTarget)

        // Verify temp file was created successfully
        assertTrue(ops.exists(tempFile), "Temp file should exist")

        // Cleanup
        ops.delete(tempFile)
    }
}
