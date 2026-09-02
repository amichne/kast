package io.github.amichne.kast.cli.runtime.bootstrap

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

internal sealed interface SidecarBootstrapAttemptLockExecution<out Value> {
    data class Executed<Value>(
        val value: Value,
    ) : SidecarBootstrapAttemptLockExecution<Value>

    data object Rejected : SidecarBootstrapAttemptLockExecution<Nothing>
    data object Interrupted : SidecarBootstrapAttemptLockExecution<Nothing>
    data object TimedOut : SidecarBootstrapAttemptLockExecution<Nothing>
}

private class JdkAcquiredSidecarBootstrapAttemptLock(
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {
    override fun close() {
        try {
            fileLock.release()
        } catch (_: IOException) {
        } finally {
            try {
                channel.close()
            } catch (_: IOException) {
            }
        }
    }
}

/** Owns creation and bounded acquisition of the exact cache-local startup lock file. */
internal object SidecarBootstrapAttemptLock {
    private const val FILE_NAME = "bootstrap-attempt.lock"
    private const val POLL_MILLIS = 50L

    fun <Value> withAcquired(
        cacheRoot: Path,
        timeout: Duration,
        operation: () -> Value,
    ): SidecarBootstrapAttemptLockExecution<Value> {
        val timeoutNanos = timeout.toNanos().takeIf { it > 0L }
            ?: return SidecarBootstrapAttemptLockExecution.Rejected
        val physicalRoot = try {
            cacheRoot.toRealPath()
        } catch (_: IOException) {
            return SidecarBootstrapAttemptLockExecution.Rejected
        } catch (_: SecurityException) {
            return SidecarBootstrapAttemptLockExecution.Rejected
        }
        if (
            physicalRoot != cacheRoot ||
            !Files.isDirectory(physicalRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            return SidecarBootstrapAttemptLockExecution.Rejected
        }
        val path = physicalRoot.resolve(FILE_NAME)
        if (Files.isSymbolicLink(path)) {
            return SidecarBootstrapAttemptLockExecution.Rejected
        }
        val channel = try {
            FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            return SidecarBootstrapAttemptLockExecution.Rejected
        } catch (_: SecurityException) {
            return SidecarBootstrapAttemptLockExecution.Rejected
        }
        val deadline = System.nanoTime() + timeoutNanos
        while (System.nanoTime() < deadline) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: IOException) {
                channel.closeQuietly()
                return SidecarBootstrapAttemptLockExecution.Rejected
            } catch (_: SecurityException) {
                channel.closeQuietly()
                return SidecarBootstrapAttemptLockExecution.Rejected
            }
            if (lock != null) {
                return JdkAcquiredSidecarBootstrapAttemptLock(channel, lock).use {
                    SidecarBootstrapAttemptLockExecution.Executed(operation())
                }
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                channel.closeQuietly()
                Thread.currentThread().interrupt()
                return SidecarBootstrapAttemptLockExecution.Interrupted
            }
        }
        channel.closeQuietly()
        return SidecarBootstrapAttemptLockExecution.TimedOut
    }

    private fun FileChannel.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }
}
