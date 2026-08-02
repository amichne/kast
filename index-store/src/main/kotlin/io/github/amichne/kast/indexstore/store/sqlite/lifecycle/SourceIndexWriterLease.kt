package io.github.amichne.kast.indexstore.store

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

class SourceIndexWriterConflictException(
    val databasePath: Path,
    val lockPath: Path,
    cause: Throwable? = null,
) : IllegalStateException(
    "Another source-index writer owns $databasePath (lock: $lockPath)",
    cause,
)

internal class SourceIndexWriterLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { lock.release() }
        channel.close()
    }

    companion object {
        fun acquire(databasePath: Path): SourceIndexWriterLease {
            val canonicalDatabasePath = databasePath.toAbsolutePath().normalize()
            Files.createDirectories(checkNotNull(canonicalDatabasePath.parent))
            val lockPath = canonicalDatabasePath.resolveSibling("${canonicalDatabasePath.fileName}.writer.lock")
            val channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (conflict: OverlappingFileLockException) {
                channel.close()
                throw SourceIndexWriterConflictException(canonicalDatabasePath, lockPath, conflict)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
            if (lock == null) {
                channel.close()
                throw SourceIndexWriterConflictException(canonicalDatabasePath, lockPath)
            }
            return SourceIndexWriterLease(channel, lock)
        }
    }
}
