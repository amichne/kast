package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Validation
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface UnixSocketOwnershipLeaseAcquisition {
    data class Acquired(val lease: UnixSocketOwnershipLease) : UnixSocketOwnershipLeaseAcquisition

    data object Owned : UnixSocketOwnershipLeaseAcquisition

    data object Rejected : UnixSocketOwnershipLeaseAcquisition

    data object ParentRejected : UnixSocketOwnershipLeaseAcquisition
}

/** Process-lifetime proof that one broker alone may prepare and own a public socket path. */
internal class UnixSocketOwnershipLease
internal constructor(
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
    internal fun acquireLease(socket: BrokerSocketPath): UnixSocketOwnershipLeaseAcquisition =
        when (socket.revalidate()) {
            is Validation.Validated -> acquireLease(socket.physicalPath)
            is Validation.Rejected -> UnixSocketOwnershipLeaseAcquisition.ParentRejected
        }

    internal fun prepare(socket: BrokerSocketPath): UnixSocketPathPreparation =
        when (socket.revalidate()) {
            is Validation.Validated -> preparePhysical(socket.physicalPath, socket.path)
            is Validation.Rejected -> UnixSocketPathPreparation.PARENT_REJECTED
        }

    internal fun acquireLease(socketPath: Path): UnixSocketOwnershipLeaseAcquisition {
        val parent = socketPath.parent ?: return UnixSocketOwnershipLeaseAcquisition.ParentRejected
        try {
            Files.createDirectories(
                parent,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
            if (parent.toRealPath() != parent || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return UnixSocketOwnershipLeaseAcquisition.ParentRejected
            }
            val lockPath = socketPath.resolveSibling("${socketPath.fileName}.lock")
            val channel =
                try {
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
    ): UnixSocketOwnershipLeaseAcquisition =
        try {
            val attributes =
                Files.readAttributes(
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
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) {
                    channel.close()
                    UnixSocketOwnershipLeaseAcquisition.Owned
                } else {
                    UnixSocketOwnershipLeaseAcquisition.Acquired(UnixSocketOwnershipLease(channel, lock))
                }
            }
        } catch (_: IOException) {
            closeRejectedChannel(channel)
        } catch (_: UnsupportedOperationException) {
            closeRejectedChannel(channel)
        } catch (_: SecurityException) {
            closeRejectedChannel(channel)
        }

    private fun closeRejectedChannel(channel: FileChannel): UnixSocketOwnershipLeaseAcquisition {
        try {
            channel.close()
        } catch (_: IOException) {
            // The rejected acquisition carries no usable ownership proof.
        }
        return UnixSocketOwnershipLeaseAcquisition.Rejected
    }

    internal fun prepare(path: Path): UnixSocketPathPreparation = preparePhysical(path, path)

    private fun preparePhysical(path: Path, transport: Path): UnixSocketPathPreparation {
        val parent = path.parent ?: return UnixSocketPathPreparation.PARENT_REJECTED
        try {
            Files.createDirectories(
                parent,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
            if (parent.toRealPath() != parent) return UnixSocketPathPreparation.PARENT_REJECTED
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return UnixSocketPathPreparation.PREPARED
            }
            if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return UnixSocketPathPreparation.REJECTED
            }
            return when (probe(transport)) {
                UnixSocketReachability.REACHABLE -> UnixSocketPathPreparation.OWNED
                // Connection refusal is liveness evidence, never an ownership grant.
                UnixSocketReachability.UNREACHABLE -> UnixSocketPathPreparation.REJECTED
                UnixSocketReachability.REJECTED -> UnixSocketPathPreparation.REJECTED
            }
        } catch (_: IOException) {
            return UnixSocketPathPreparation.REJECTED
        } catch (_: SecurityException) {
            return UnixSocketPathPreparation.REJECTED
        }
    }

    private fun probe(path: Path): UnixSocketReachability {
        val channel =
            try {
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

internal sealed interface PublishedAliasCapture {
    data class Captured(val socket: OwnedUnixSocket) : PublishedAliasCapture

    data object Unproven : PublishedAliasCapture
}

internal class OwnedUnixSocket
private constructor(
    private val path: Path,
    private val fileKey: Any,
    private val aliasTarget: SocketTarget? = null,
) {
    internal fun matchesCurrent(): Boolean {
        return try {
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (current.fileKey() != fileKey) return false
            when (val target = aliasTarget) {
                null -> current.isOther && !current.isSymbolicLink
                else -> {
                    if (!current.isSymbolicLink || path.toRealPath() != target.path) return false
                    val resolved =
                        Files.readAttributes(target.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    resolved.isOther && !resolved.isSymbolicLink && resolved.fileKey() == target.fileKey
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun retire() {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            val current =
                Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    .fileKey() ?: return
            if (current == fileKey) Files.deleteIfExists(path)
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }
    }

    companion object {
        internal fun capturePublishedAlias(
            socket: BrokerSocketPath,
            processPid: Long,
            timeoutMillis: Long,
        ): PublishedAliasCapture =
            try {
                if (socket.revalidate() is Validation.Rejected) return PublishedAliasCapture.Unproven
                val alias = socket.physicalPath
                val aliasAttributes =
                    Files.readAttributes(alias, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!aliasAttributes.isSymbolicLink) return PublishedAliasCapture.Unproven
                val aliasKey = aliasAttributes.fileKey() ?: return PublishedAliasCapture.Unproven
                val target = alias.toRealPath()
                val targetAttributes =
                    Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!targetAttributes.isOther || targetAttributes.isSymbolicLink) return PublishedAliasCapture.Unproven
                val targetKey = targetAttributes.fileKey() ?: return PublishedAliasCapture.Unproven
                if (!ProcessUnixSocketOwnership.holds(processPid, target, timeoutMillis)) {
                    return PublishedAliasCapture.Unproven
                }
                val owned = OwnedUnixSocket(alias, aliasKey, SocketTarget(target, targetKey))
                if (owned.matchesCurrent()) PublishedAliasCapture.Captured(owned) else PublishedAliasCapture.Unproven
            } catch (_: IOException) {
                PublishedAliasCapture.Unproven
            } catch (_: SecurityException) {
                PublishedAliasCapture.Unproven
            }

        internal fun capture(socket: BrokerSocketPath): OwnedUnixSocket? =
            when (socket.revalidate()) {
                is Validation.Validated -> capture(socket.physicalPath)
                is Validation.Rejected -> null
            }

        internal fun capture(path: Path): OwnedUnixSocket? =
            try {
                val attributes =
                    Files.readAttributes(
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

private data class SocketTarget(val path: Path, val fileKey: Any)

/** Observes the launched process's open Unix socket, rather than inferring ownership from a successful connection. */
private object ProcessUnixSocketOwnership {
    fun holds(pid: Long, target: Path, timeoutMillis: Long): Boolean {
        if (pid <= 0 || timeoutMillis <= 0) return false
        val lsof =
            listOf("/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { Files.isExecutable(Path.of(it)) } ?: return false
        var probe: Process? = null
        try {
            probe =
                ProcessBuilder(lsof, "-nP", "-a", "-p", pid.toString(), "-U", "-F", "pfn", target.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!probe.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return false
            }
            if (probe.exitValue() != 0) return false
            val lines = probe.inputStream.bufferedReader().use { it.readLines() }
            return lines.firstOrNull() == "p$pid" && lines.any { it == "n$target" }
        } catch (_: IOException) {
            return false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: SecurityException) {
            return false
        } finally {
            if (probe?.isAlive == true) probe.destroyForcibly()
        }
    }
}

private enum class UnixSocketReachability {
    REACHABLE,
    UNREACHABLE,
    REJECTED,
}
