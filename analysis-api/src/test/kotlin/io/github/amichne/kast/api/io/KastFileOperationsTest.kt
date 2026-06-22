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

class KastFileOperationsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local disk file operations read write list and delete through abstraction`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val testFile = tempDir.resolve("test.txt").toString()
        val testDir = tempDir.toString()

        assertFalse(ops.exists(testFile), "File should not exist before creation")

        val content = "Hello, Kast filesystem abstraction!"
        ops.writeText(testFile, content)

        assertTrue(ops.exists(testFile), "File should exist after writeText")

        val readContent = ops.readText(testFile)
        assertEquals(content, readContent, "Read content should match written content")

        val children = ops.list(testDir)
        assertTrue(children.any { it.endsWith("test.txt") },
            "Directory listing should include test.txt")

        val deleted = ops.delete(testFile)
        assertTrue(deleted, "delete should return true for existing file")

        assertFalse(ops.exists(testFile), "File should not exist after deletion")

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

        ops.writeText(testFile, "initial content")

        val newContent = "new content"
        ops.writeText(testFile, newContent)

        val readContent = ops.readText(testFile)
        assertEquals(newContent, readContent, "File should contain only new content after overwrite")
    }

    @Test
    fun `list returns empty list for empty directory`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val emptyDir = tempDir.resolve("empty").toString()

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

    @Test
    fun `writeText creates parent directories if they do not exist`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val nestedFile = tempDir.resolve("deeply/nested/path/test.txt").toString()

        val parentDir = tempDir.resolve("deeply/nested/path").toString()
        assertFalse(ops.exists(parentDir), "Parent directories should not exist before writeText")

        val content = "Content in nested location"
        ops.writeText(nestedFile, content)

        assertTrue(ops.exists(nestedFile), "File should exist after writeText")
        assertTrue(ops.exists(parentDir), "Parent directories should be created")

        val readContent = ops.readText(nestedFile)
        assertEquals(content, readContent, "Content should match what was written")
    }

    @Test
    fun `list returns absolute paths`() {
        val ops: KastFileOperations = LocalDiskFileOperations
        val testDir = tempDir.toString()
        val testFile = tempDir.resolve("absolute-test.txt").toString()

        ops.writeText(testFile, "test content")

        val children = ops.list(testDir)

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
