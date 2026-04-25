package io.github.amichne.kast.testing

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import io.github.amichne.kast.api.io.KastFileOperations
import java.nio.file.FileSystem
import java.nio.file.Files

/**
 * In-memory implementation of KastFileOperations backed by Jimfs.
 * Used for testing descriptor/edit operations without touching real filesystem.
 */
class JimfsFileOperations(private val fileSystem: FileSystem) : KastFileOperations {

    // Instance-scoped locks keyed by path for Jimfs
    // Each JimfsFileOperations instance has its own lock map, providing fixture isolation
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun readText(path: String): String {
        val pathObj = fileSystem.getPath(path)
        if (!Files.exists(pathObj)) {
            throw java.io.FileNotFoundException("File not found: $path")
        }
        return Files.readString(pathObj)
    }

    override fun writeText(path: String, content: String) {
        val pathObj = fileSystem.getPath(path)
        // Create parent directories if needed
        pathObj.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }
        Files.writeString(pathObj, content)
    }

    override fun exists(path: String): Boolean {
        val pathObj = fileSystem.getPath(path)
        return Files.exists(pathObj)
    }

    override fun list(path: String): List<String> {
        val pathObj = fileSystem.getPath(path)
        return Files.list(pathObj).use { stream ->
            stream.map { it.toAbsolutePath().toString() }.toList()
        }
    }

    override fun delete(path: String): Boolean {
        val pathObj = fileSystem.getPath(path)
        return try {
            Files.deleteIfExists(pathObj)
        } catch (_: java.nio.file.DirectoryNotEmptyException) {
            false
        }
    }

    override fun createTempFile(targetPath: String): String {
        val targetPathObj = fileSystem.getPath(targetPath)
        val parentDir = targetPathObj.parent ?: fileSystem.getPath(".")

        // Ensure parent directory exists
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        // Create temp file in same directory as target for atomic move
        val tempFile = Files.createTempFile(
            parentDir,
            ".kast-tmp-",
            ".tmp"
        )
        return tempFile.toAbsolutePath().toString()
    }

    override fun moveAtomic(sourcePath: String, destPath: String) {
        val sourcePathObj = fileSystem.getPath(sourcePath)
        val destPathObj = fileSystem.getPath(destPath)

        // ATOMIC_MOVE ensures crash safety - either the move completes or it doesn't
        // REPLACE_EXISTING allows updating existing files atomically
        Files.move(
            sourcePathObj,
            destPathObj,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    override fun <T> withLock(path: String, block: () -> T): T {
        // For Jimfs, use instance-scoped in-memory lock keyed by path
        // This provides thread-safe access within the same instance while maintaining fixture isolation
        // (Jimfs FileLock may not work as expected since it's in-memory)
        val lockObject = locks.computeIfAbsent(path) { Any() }
        return synchronized(lockObject) {
            block()
        }
    }
}

/**
 * Test fixture for in-memory file operations.
 * Provides isolated filesystem backed by Jimfs.
 */
data class InMemoryFileOperationsFixture(
    val fileSystem: FileSystem,
    val fileOps: KastFileOperations,
    val root: String
) {
    /**
     * Create a file with content at the given path.
     */
    fun createFile(path: String, content: String) {
        fileOps.writeText(path, content)
    }

    /**
     * Create a directory at the given path.
     */
    fun createDir(path: String) {
        val pathObj = fileSystem.getPath(path)
        Files.createDirectories(pathObj)
    }
}

/**
 * Factory function to create isolated in-memory file operations fixture.
 * Each call returns a new isolated filesystem instance.
 * Uses Unix-style configuration for consistency across platforms.
 */
fun inMemoryFileOperations(): InMemoryFileOperationsFixture {
    val fileSystem = Jimfs.newFileSystem(Configuration.unix())
    val fileOps = JimfsFileOperations(fileSystem)
    val root = fileSystem.rootDirectories.first().toString()
    return InMemoryFileOperationsFixture(fileSystem, fileOps, root)
}
