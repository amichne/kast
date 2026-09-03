package io.github.amichne.kast.cli.broker.runtime

import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface UnixSocketOwnershipLeaseAcquisition {
    data class Acquired(val lease: UnixSocketOwnershipLease) : UnixSocketOwnershipLeaseAcquisition
    data object Owned : UnixSocketOwnershipLeaseAcquisition
    data object Rejected : UnixSocketOwnershipLeaseAcquisition
    data object ParentRejected : UnixSocketOwnershipLeaseAcquisition
}

/** Process-lifetime proof that one broker alone may prepare and own a public socket path. */
internal class UnixSocketOwnershipLease internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lock.release()
        } catch (_: IOException) {
            // Closing the channel below still releases an operating-system-owned lock.
        } finally {
            try {
                channel.close()
            } catch (_: IOException) {
                // The lease is already unusable after close begins.
            }
        }
    }
}

internal enum class UnixSocketPathPreparation {
    PREPARED,
    OWNED,
    REJECTED,
    PARENT_REJECTED,
}

internal object UnixSocketPathOwnership {
    internal fun acquireLease(socketPath: Path): UnixSocketOwnershipLeaseAcquisition {
        val parent = socketPath.parent
            ?: return UnixSocketOwnershipLeaseAcquisition.ParentRejected
        try {
            Files.createDirectories(parent)
            if (
                parent.toRealPath() != parent ||
                !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
            ) {
                return UnixSocketOwnershipLeaseAcquisition.ParentRejected
            }
            val lockPath = socketPath.resolveSibling("${socketPath.fileName}.lock")
            val channel = try {
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: IOException) {
                return UnixSocketOwnershipLeaseAcquisition.Rejected
            } catch (_: UnsupportedOperationException) {
                return UnixSocketOwnershipLeaseAcquisition.Rejected
            } catch (_: SecurityException) {
                return UnixSocketOwnershipLeaseAcquisition.Rejected
            }
            return acquireOpenedLease(channel, lockPath)
        } catch (_: IOException) {
            return UnixSocketOwnershipLeaseAcquisition.Rejected
        } catch (_: UnsupportedOperationException) {
            return UnixSocketOwnershipLeaseAcquisition.Rejected
        } catch (_: SecurityException) {
            return UnixSocketOwnershipLeaseAcquisition.Rejected
        }
    }

    private fun acquireOpenedLease(
        channel: FileChannel,
        lockPath: Path,
    ): UnixSocketOwnershipLeaseAcquisition = try {
        val attributes = Files.readAttributes(
            lockPath,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            channel.close()
            UnixSocketOwnershipLeaseAcquisition.Rejected
        } else {
            Files.setPosixFilePermissions(
                lockPath,
                PosixFilePermissions.fromString("rw-------"),
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                UnixSocketOwnershipLeaseAcquisition.Owned
            } else {
                UnixSocketOwnershipLeaseAcquisition.Acquired(
                    UnixSocketOwnershipLease(channel, lock),
                )
            }
        }
    } catch (_: IOException) {
        closeRejectedChannel(channel)
    } catch (_: UnsupportedOperationException) {
        closeRejectedChannel(channel)
    } catch (_: SecurityException) {
        closeRejectedChannel(channel)
    }

    private fun closeRejectedChannel(
        channel: FileChannel,
    ): UnixSocketOwnershipLeaseAcquisition {
        try {
            channel.close()
        } catch (_: IOException) {
            // The rejected acquisition carries no usable ownership proof.
        }
        return UnixSocketOwnershipLeaseAcquisition.Rejected
    }

    internal fun prepare(path: Path): UnixSocketPathPreparation {
        val parent = path.parent ?: return UnixSocketPathPreparation.PARENT_REJECTED
        try {
            Files.createDirectories(parent)
            if (parent.toRealPath() != parent) return UnixSocketPathPreparation.PARENT_REJECTED
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return UnixSocketPathPreparation.PREPARED
            }
            if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return UnixSocketPathPreparation.REJECTED
            }
            return when (probe(path)) {
                UnixSocketReachability.REACHABLE -> UnixSocketPathPreparation.OWNED
                UnixSocketReachability.UNREACHABLE -> {
                    Files.delete(path)
                    UnixSocketPathPreparation.PREPARED
                }
                UnixSocketReachability.REJECTED -> UnixSocketPathPreparation.REJECTED
            }
        } catch (_: IOException) {
            return UnixSocketPathPreparation.REJECTED
        } catch (_: SecurityException) {
            return UnixSocketPathPreparation.REJECTED
        }
    }

    private fun probe(path: Path): UnixSocketReachability {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: Exception) {
            return UnixSocketReachability.REJECTED
        }
        return channel.use { socket ->
            try {
                socket.connect(UnixDomainSocketAddress.of(path))
                UnixSocketReachability.REACHABLE
            } catch (_: ConnectException) {
                UnixSocketReachability.UNREACHABLE
            } catch (_: IOException) {
                UnixSocketReachability.REJECTED
            } catch (_: SecurityException) {
                UnixSocketReachability.REJECTED
            }
        }
    }
}

internal class OwnedUnixSocket private constructor(
    private val path: Path,
    private val fileKey: Any,
) {
    internal fun retire() {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            val current = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey() ?: return
            if (current == fileKey) Files.deleteIfExists(path)
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }
    }

    companion object {
        internal fun capture(path: Path): OwnedUnixSocket? = try {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            val key = attributes.fileKey() ?: return null
            if (!attributes.isOther || attributes.isSymbolicLink) return null
            OwnedUnixSocket(path, key)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

private enum class UnixSocketReachability { REACHABLE, UNREACHABLE, REJECTED }
