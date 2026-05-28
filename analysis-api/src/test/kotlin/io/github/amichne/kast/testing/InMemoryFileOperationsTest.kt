package io.github.amichne.kast.testing

import io.github.amichne.kast.api.io.KastFileOperations
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * RED test for in-memory file operations fixture.
 * This test MUST fail initially, proving we need the fixture implementation.
 */
class InMemoryFileOperationsTest {

    @Test
    fun `should write and read text without touching real filesystem`() {
        // Arrange - this will fail because inMemoryFileOperations() doesn't exist yet
        val fixture = inMemoryFileOperations()
        val testPath = "${fixture.root}/test.txt"
        val content = "Hello, Jimfs!"

        // Act
        fixture.fileOps.writeText(testPath, content)
        val readContent = fixture.fileOps.readText(testPath)

        // Assert
        assertEquals(content, readContent)
        assertTrue(fixture.fileOps.exists(testPath))
    }

    @Test
    fun `should list directory contents in memory`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val dirPath = "${fixture.root}/testdir"
        fixture.createDir(dirPath)
        val file1Path = "$dirPath/file1.txt"
        val file2Path = "$dirPath/file2.txt"

        // Act
        fixture.createFile(file1Path, "content1")
        fixture.createFile(file2Path, "content2")
        val children = fixture.fileOps.list(dirPath)

        // Assert
        assertEquals(2, children.size)
        assertTrue(children.any { it.endsWith("file1.txt") })
        assertTrue(children.any { it.endsWith("file2.txt") })
    }

    @Test
    fun `should delete files in memory`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val testPath = "${fixture.root}/delete-me.txt"
        fixture.createFile(testPath, "temporary")

        // Act
        val deleted = fixture.fileOps.delete(testPath)

        // Assert
        assertTrue(deleted)
        assertFalse(fixture.fileOps.exists(testPath))
    }

    @Test
    fun `should isolate each fixture instance`() {
        // Arrange
        val fixture1 = inMemoryFileOperations()
        val fixture2 = inMemoryFileOperations()
        val path1 = "${fixture1.root}/isolated1.txt"
        val path2 = "${fixture2.root}/isolated2.txt"

        // Act
        fixture1.createFile(path1, "fixture1")
        fixture2.createFile(path2, "fixture2")

        // Assert
        assertTrue(fixture1.fileOps.exists(path1))
        assertFalse(fixture1.fileOps.exists(path2))
        assertTrue(fixture2.fileOps.exists(path2))
        assertFalse(fixture2.fileOps.exists(path1))
    }

    /**
     * Contract test: Verify writeText creates parent directories.
     * This ensures JimfsFileOperations matches the KastFileOperations contract.
     */
    @Test
    fun `writeText creates parent directories if they do not exist`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val nestedFile = "${fixture.root}/deeply/nested/path/test.txt"
        val parentDir = "${fixture.root}/deeply/nested/path"

        // Verify parent doesn't exist initially
        assertFalse(fixture.fileOps.exists(parentDir))

        // Act - write to file in non-existent directory structure
        val content = "Content in nested location"
        fixture.fileOps.writeText(nestedFile, content)

        // Assert - file and parents were created
        assertTrue(fixture.fileOps.exists(nestedFile))
        assertTrue(fixture.fileOps.exists(parentDir))
        assertEquals(content, fixture.fileOps.readText(nestedFile))
    }

    /**
     * Contract test: Verify list() returns absolute paths.
     * This ensures the contract is unambiguous for JimfsFileOperations.
     */
    @Test
    fun `list returns absolute paths`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val testDir = "${fixture.root}/testdir"
        fixture.createDir(testDir)
        fixture.createFile("$testDir/file1.txt", "content1")
        fixture.createFile("$testDir/file2.txt", "content2")

        // Act
        val children = fixture.fileOps.list(testDir)

        // Assert - all paths should be absolute (start with root)
        assertEquals(2, children.size)
        children.forEach { path ->
            assertTrue(path.startsWith(fixture.root),
                "Path should be absolute (start with ${fixture.root}), but got: $path")
        }
    }

    /**
     * Test atomic write operations in memory.
     * Verifies createTempFile + writeText + moveAtomic pattern works with Jimfs.
     */
    @Test
    fun `createTempFile and moveAtomic provide atomic write semantics in memory`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val targetFile = "${fixture.root}/target.txt"
        val content = "Atomic content"

        // Act - Atomic write pattern
        val tempFile = fixture.fileOps.createTempFile(targetFile)
        fixture.fileOps.writeText(tempFile, content)
        fixture.fileOps.moveAtomic(tempFile, targetFile)

        // Assert - Target exists with correct content, temp file is gone
        assertTrue(fixture.fileOps.exists(targetFile))
        assertEquals(content, fixture.fileOps.readText(targetFile))
        assertFalse(fixture.fileOps.exists(tempFile))
    }

    /**
     * Test that moveAtomic replaces existing file in memory.
     */
    @Test
    fun `moveAtomic replaces existing file atomically in memory`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val targetFile = "${fixture.root}/replace-target.txt"
        fixture.createFile(targetFile, "initial content")

        // Act - Replace with atomic move
        val tempFile = fixture.fileOps.createTempFile(targetFile)
        val newContent = "new content"
        fixture.fileOps.writeText(tempFile, newContent)
        fixture.fileOps.moveAtomic(tempFile, targetFile)

        // Assert - Target has new content
        assertEquals(newContent, fixture.fileOps.readText(targetFile))
        assertFalse(fixture.fileOps.exists(tempFile))
    }

    /**
     * Test that createTempFile creates parent directories in memory.
     */
    @Test
    fun `createTempFile creates parent directories automatically in memory`() {
        // Arrange
        val fixture = inMemoryFileOperations()
        val nestedTarget = "${fixture.root}/nested/dir/target.txt"

        // Act - Create temp file for nested target
        val tempFile = fixture.fileOps.createTempFile(nestedTarget)

        // Assert - Temp file exists
        assertTrue(fixture.fileOps.exists(tempFile))

        // Cleanup
        fixture.fileOps.delete(tempFile)
    }

    /**
     * RED test: Separate fixtures MUST NOT share lock state.
     * Under static locks, fixture B blocks until A releases.
     * With instance locks, B enters promptly.
     *
     * This test uses latches to avoid flaky timing:
     * - Fixture A enters withLock and signals it's holding the lock
     * - Fixture B attempts withLock on the same string path
     * - B should enter promptly if locks are instance-scoped
     * - If locks are static, B blocks and we timeout
     */
    @Test
    fun `separate fixtures must not share lock state`() {
        // Arrange
        val fixtureA = inMemoryFileOperations()
        val fixtureB = inMemoryFileOperations()
        val samePath = "/shared-path/file.txt"

        val aHoldingLock = java.util.concurrent.CountDownLatch(1)
        val aReleaseLock = java.util.concurrent.CountDownLatch(1)
        val bAcquiredLock = java.util.concurrent.CountDownLatch(1)

        // Act - Thread A holds lock indefinitely
        val threadA = Thread {
            fixtureA.fileOps.withLock(samePath) {
                aHoldingLock.countDown() // Signal A is holding lock
                aReleaseLock.await(5, java.util.concurrent.TimeUnit.SECONDS) // Wait to release
            }
        }
        threadA.start()

        // Wait for A to acquire lock
        assertTrue(aHoldingLock.await(1, java.util.concurrent.TimeUnit.SECONDS),
            "Thread A should acquire lock promptly")

        // Act - Thread B attempts to acquire lock on separate fixture
        val threadB = Thread {
            fixtureB.fileOps.withLock(samePath) {
                bAcquiredLock.countDown() // Signal B acquired lock
            }
        }
        threadB.start()

        // Assert - B should acquire lock promptly (500ms) if locks are instance-scoped
        // If locks are static/shared, B will block until A releases or timeout
        val bAcquired = bAcquiredLock.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        // Cleanup
        aReleaseLock.countDown() // Release A
        threadA.join(1000)
        threadB.join(1000)

        // Assert
        assertTrue(bAcquired,
            "Fixture B should acquire lock promptly on its own instance. " +
            "Blocking indicates locks are shared across fixtures (static companion object).")
    }
}
