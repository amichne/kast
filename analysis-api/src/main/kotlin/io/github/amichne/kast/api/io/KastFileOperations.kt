package io.github.amichne.kast.api.io

/**
 * Abstraction for filesystem operations in Kast.
 *
 * This interface enables testing and alternative implementations beyond local disk,
 * such as in-memory filesystems or remote storage.
 */
interface KastFileOperations {
    /**
     * Read the entire content of a file as text.
     * @param path String path to the file
     * @return File content as String
     * @throws FileNotFoundException if the file does not exist
     */
    fun readText(path: String): String

    /**
     * Write text content to a file, creating it if necessary.
     *
     * If parent directories do not exist, they will be created automatically.
     * If the file already exists, its content will be overwritten.
     *
     * @param path String path to the file
     * @param content Text content to write
     */
    fun writeText(path: String, content: String)

    /**
     * Check whether a file or directory exists at the given path.
     * @param path String path to check
     * @return true if exists, false otherwise
     */
    fun exists(path: String): Boolean

    /**
     * List all direct children of a directory.
     *
     * Returns absolute paths for all children. Does not recurse into subdirectories.
     *
     * @param path String path to the directory
     * @return List of absolute child paths as Strings
     * @throws NotADirectoryException if path exists but is not a directory
     */
    fun list(path: String): List<String>

    /**
     * Delete a file or empty directory.
     * @param path String path to delete
     * @return true if the file was deleted, false if it didn't exist
     */
    fun delete(path: String): Boolean

    /**
     * Create a temporary file in the same directory as the target path.
     *
     * The temp file will have a unique name to avoid collisions.
     * Creating the temp file in the same directory as the target ensures
     * that a subsequent [moveAtomic] call will stay on the same filesystem,
     * which is required for atomic move operations.
     *
     * Caller is responsible for cleanup (via delete or move).
     *
     * @param targetPath String path where the final file will be located
     * @return String path to the created temporary file
     */
    fun createTempFile(targetPath: String): String

    /**
     * Move a file atomically from source to destination.
     *
     * This method provides **strict atomic move semantics** with crash safety guarantees.
     * The move either completes fully or not at all - no partial writes or torn states.
     *
     * **Replacement Semantics:**
     * If the destination exists, it will be **replaced atomically** during the move.
     * This is intended for replacing a target file **after** the caller has already
     * performed conflict detection and hash validation. Do NOT use this method as
     * the conflict-detection mechanism itself (e.g., for CreateFile semantics that
     * should fail if the file exists). Use [exists] checks before calling this method
     * if you need CreateFile-style fail-on-exists behavior.
     *
     * **Atomic Move Requirements:**
     * - Source and destination must be on the same filesystem
     * - Use [createTempFile] to ensure temp files are in the same directory as target
     * - This method will NOT fall back to non-atomic operations if atomic move fails
     *
     * @param sourcePath String path to source file
     * @param destPath String path to destination file
     * @throws java.nio.file.AtomicMoveNotSupportedException if the underlying filesystem
     *         does not support atomic moves (e.g., moving across filesystem boundaries)
     * @throws java.io.IOException for other I/O errors during the move operation
     */
    fun moveAtomic(sourcePath: String, destPath: String)

    /**
     * Execute a block while holding an exclusive lock on a file path.
     *
     * This method provides cross-instance and cross-process synchronization for
     * read-modify-write operations on a file. Multiple instances or processes
     * attempting to lock the same path will be serialized.
     *
     * **Use Cases:**
     * - Read-modify-write operations that must be atomic across instances
     * - Preventing lost updates in multi-instance/multi-process scenarios
     *
     * **Lock Semantics:**
     * - Lock is exclusive (write lock)
     * - Lock file is created in same directory as target with ".lock" suffix
     * - Lock is automatically released when block completes (normal or exceptional)
     * - Lock acquisition blocks until available with no timeout
     * - Intended for local filesystem operations with brief contention
     * - NOT designed for distributed or network lock semantics
     * - For LocalDiskFileOperations: Uses FileChannel/FileLock (cross-process)
     * - For JimfsFileOperations: Uses in-memory lock keyed by path (cross-instance, same JVM)
     *
     * **Lock File Persistence:**
     * - Lock file `${path}.lock` may persist after release
     * - Persistent lock files are harmless housekeeping state
     * - They do not indicate active locks or prevent future acquisition
     *
     * **Example:**
     * ```kotlin
     * fileOps.withLock("/path/to/file.json") {
     *     val content = fileOps.readText("/path/to/file.json")
     *     val modified = transform(content)
     *     fileOps.writeText("/path/to/file.json", modified)
     * }
     * ```
     *
     * @param path String path to lock (not necessarily existing file)
     * @param block Code to execute while holding the lock
     * @return Result of the block execution
     */
    fun <T> withLock(path: String, block: () -> T): T
}

/**
 * Local disk implementation backed by java.nio.file.
 */
object LocalDiskFileOperations : KastFileOperations {
    override fun readText(path: String): String {
        val pathObj = java.nio.file.Path.of(path)
        if (!java.nio.file.Files.exists(pathObj)) {
            throw java.io.FileNotFoundException("File not found: $path")
        }
        return java.nio.file.Files.readString(pathObj)
    }

    override fun writeText(path: String, content: String) {
        val pathObj = java.nio.file.Path.of(path)
        // Create parent directories if needed
        pathObj.parent?.let { parent ->
            if (!java.nio.file.Files.exists(parent)) {
                java.nio.file.Files.createDirectories(parent)
            }
        }
        java.nio.file.Files.writeString(pathObj, content)
    }

    override fun exists(path: String): Boolean {
        val pathObj = java.nio.file.Path.of(path)
        return java.nio.file.Files.exists(pathObj)
    }

    override fun list(path: String): List<String> {
        val pathObj = java.nio.file.Path.of(path)
        return java.nio.file.Files.list(pathObj).use { stream ->
            stream.map { it.toAbsolutePath().toString() }.toList()
        }
    }

    override fun delete(path: String): Boolean {
        val pathObj = java.nio.file.Path.of(path)
        return try {
            java.nio.file.Files.deleteIfExists(pathObj)
        } catch (_: java.nio.file.DirectoryNotEmptyException) {
            false
        }
    }

    override fun createTempFile(targetPath: String): String {
        val targetPathObj = java.nio.file.Path.of(targetPath)
        val parentDir = targetPathObj.parent ?: java.nio.file.Path.of(".")

        // Ensure parent directory exists
        if (!java.nio.file.Files.exists(parentDir)) {
            java.nio.file.Files.createDirectories(parentDir)
        }

        // Create temp file in same directory as target for atomic move
        val tempFile = java.nio.file.Files.createTempFile(
            parentDir,
            ".kast-tmp-",
            ".tmp"
        )
        return tempFile.toAbsolutePath().toString()
    }

    override fun moveAtomic(sourcePath: String, destPath: String) {
        val sourcePathObj = java.nio.file.Path.of(sourcePath)
        val destPathObj = java.nio.file.Path.of(destPath)

        // ATOMIC_MOVE ensures crash safety - either the move completes or it doesn't
        // REPLACE_EXISTING allows updating existing files atomically
        java.nio.file.Files.move(
            sourcePathObj,
            destPathObj,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    override fun <T> withLock(path: String, block: () -> T): T {
        val lockPath = java.nio.file.Path.of("$path.lock")

        // Ensure parent directory exists for lock file
        lockPath.parent?.let { parent ->
            if (!java.nio.file.Files.exists(parent)) {
                java.nio.file.Files.createDirectories(parent)
            }
        }

        // Create or open lock file
        // Using CREATE to ensure file exists, but multiple processes can open same file
        val lockFile = java.nio.file.Files.newByteChannel(
            lockPath,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE
        ) as java.nio.channels.FileChannel

        return lockFile.use { channel ->
            // Acquire exclusive lock - blocks until available
            // This is cross-process safe on most filesystems
            channel.lock().use {
                block()
            }
        }
    }
}
