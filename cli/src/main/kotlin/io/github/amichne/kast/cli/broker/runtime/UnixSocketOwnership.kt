package io.github.amichne.kast.cli.broker.runtime

import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal enum class UnixSocketPathPreparation {
    PREPARED,
    OWNED,
    REJECTED,
    PARENT_REJECTED,
}

internal object UnixSocketPathOwnership {
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
