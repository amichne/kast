package io.github.amichne.kast.testing

import io.github.amichne.kast.api.io.KastFileOperations
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class InMemoryFileOperationsTest {

    @Test
    fun `should write and read text without touching real filesystem`() {
        val fixture = inMemoryFileOperations()
        val testPath = "${fixture.root}/test.txt"
        val content = "Hello, Jimfs!"

        fixture.fileOps.writeText(testPath, content)
        val readContent = fixture.fileOps.readText(testPath)

        assertEquals(content, readContent)
        assertTrue(fixture.fileOps.exists(testPath))
    }

    @Test
    fun `should list directory contents in memory`() {
        val fixture = inMemoryFileOperations()
        val dirPath = "${fixture.root}/testdir"
        fixture.createDir(dirPath)
        val file1Path = "$dirPath/file1.txt"
        val file2Path = "$dirPath/file2.txt"

        fixture.createFile(file1Path, "content1")
        fixture.createFile(file2Path, "content2")
        val children = fixture.fileOps.list(dirPath)

        assertEquals(2, children.size)
        assertTrue(children.any { it.endsWith("file1.txt") })
        assertTrue(children.any { it.endsWith("file2.txt") })
    }

    @Test
    fun `should delete files in memory`() {
        val fixture = inMemoryFileOperations()
        val testPath = "${fixture.root}/delete-me.txt"
        fixture.createFile(testPath, "temporary")

        val deleted = fixture.fileOps.delete(testPath)

        assertTrue(deleted)
        assertFalse(fixture.fileOps.exists(testPath))
    }

    @Test
    fun `should isolate each fixture instance`() {
        val fixture1 = inMemoryFileOperations()
        val fixture2 = inMemoryFileOperations()
        val path1 = "${fixture1.root}/isolated1.txt"
        val path2 = "${fixture2.root}/isolated2.txt"

        fixture1.createFile(path1, "fixture1")
        fixture2.createFile(path2, "fixture2")

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
        val fixture = inMemoryFileOperations()
        val nestedFile = "${fixture.root}/deeply/nested/path/test.txt"
        val parentDir = "${fixture.root}/deeply/nested/path"

        // Verify parent doesn't exist initially
        assertFalse(fixture.fileOps.exists(parentDir))

        val content = "Content in nested location"
        fixture.fileOps.writeText(nestedFile, content)

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
        val fixture = inMemoryFileOperations()
        val testDir = "${fixture.root}/testdir"
        fixture.createDir(testDir)
        fixture.createFile("$testDir/file1.txt", "content1")
        fixture.createFile("$testDir/file2.txt", "content2")

        val children = fixture.fileOps.list(testDir)

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
        val fixture = inMemoryFileOperations()
        val targetFile = "${fixture.root}/target.txt"
        val content = "Atomic content"

        val tempFile = fixture.fileOps.createTempFile(targetFile)
        fixture.fileOps.writeText(tempFile, content)
        fixture.fileOps.moveAtomic(tempFile, targetFile)

        assertTrue(fixture.fileOps.exists(targetFile))
        assertEquals(content, fixture.fileOps.readText(targetFile))
        assertFalse(fixture.fileOps.exists(tempFile))
    }

    /**
     * Test that moveAtomic replaces existing file in memory.
     */
    @Test
    fun `moveAtomic replaces existing file atomically in memory`() {
        val fixture = inMemoryFileOperations()
        val targetFile = "${fixture.root}/replace-target.txt"
        fixture.createFile(targetFile, "initial content")

        val tempFile = fixture.fileOps.createTempFile(targetFile)
        val newContent = "new content"
        fixture.fileOps.writeText(tempFile, newContent)
        fixture.fileOps.moveAtomic(tempFile, targetFile)

        assertEquals(newContent, fixture.fileOps.readText(targetFile))
        assertFalse(fixture.fileOps.exists(tempFile))
    }

    /**
     * Test that createTempFile creates parent directories in memory.
     */
    @Test
    fun `createTempFile creates parent directories automatically in memory`() {
        val fixture = inMemoryFileOperations()
        val nestedTarget = "${fixture.root}/nested/dir/target.txt"

        val tempFile = fixture.fileOps.createTempFile(nestedTarget)

        assertTrue(fixture.fileOps.exists(tempFile))

        fixture.fileOps.delete(tempFile)
    }

    /**
     * Uses latches to avoid flaky timing:
     * - Fixture A enters withLock and signals it's holding the lock
     * - Fixture B attempts withLock on the same string path
     * - B should enter promptly if locks are instance-scoped
     */
    @Test
    fun `separate fixtures must not share lock state`() {
        val fixtureA = inMemoryFileOperations()
        val fixtureB = inMemoryFileOperations()
        val samePath = "/shared-path/file.txt"

        val aHoldingLock = java.util.concurrent.CountDownLatch(1)
        val aReleaseLock = java.util.concurrent.CountDownLatch(1)
        val bAcquiredLock = java.util.concurrent.CountDownLatch(1)

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
