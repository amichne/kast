package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One lock-held endpoint; retirement removes only the exact files created by this owner. */
internal class OwnedHostedEndpoint
private constructor(
    val server: ServerSocketChannel,
    val socket: Path,
    private val socketKey: Any,
    private val descriptor: Path,
    private val descriptorKey: Any,
    private val lock: FileLock,
    private val lockChannel: FileChannel,
) : AutoCloseable {
    override fun close() {
        try {
            server.close()
        } finally {
            try {
                try {
                    if (Files.exists(descriptor, NOFOLLOW_LINKS) && key(descriptor) == descriptorKey)
                        Files.delete(descriptor)
                } finally {
                    if (Files.exists(socket, NOFOLLOW_LINKS) && key(socket) == socketKey) Files.delete(socket)
                }
            } finally {
                try {
                    lock.release()
                } finally {
                    lockChannel.close()
                }
            }
        }
    }

    companion object {
        fun directory(home: Path, root: CanonicalWorkspaceRoot): Path {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(root.value.toByteArray(Charsets.UTF_8))
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
            return home.resolve(".kast/ide-hosted/$digest")
        }

        fun open(
            directory: Path,
            root: CanonicalWorkspaceRoot,
            host: IdeReadHostLifetime,
            observer: HostedEndpointObserver = HostedEndpointObserver { _, _ -> },
            limits: ReadLimits = ReadLimits.Default,
            advertisement: HostedEndpointAdvertisement = HostedEndpointAdvertisement.SEMANTIC,
        ): Refinement<OwnedHostedEndpoint, HostedEndpointFailure> {
            val socket = directory.resolve("host.sock")
            val descriptor = directory.resolve("endpoint.json")
            try {
                if (
                    !directory.isAbsolute ||
                        directory.normalize() != directory ||
                        socket.toString().toByteArray().size > 100
                ) {
                    return Refinement.Rejected(HostedEndpointFailure.DIRECTORY_REJECTED)
                }
                var ancestor: Path? = directory
                while (ancestor != null) {
                    if (Files.isSymbolicLink(ancestor))
                        return Refinement.Rejected(HostedEndpointFailure.DIRECTORY_REJECTED)
                    ancestor = ancestor.parent
                }
                Files.createDirectories(directory)
                if (directory.toRealPath() != directory)
                    return Refinement.Rejected(HostedEndpointFailure.DIRECTORY_REJECTED)
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
                val channel =
                    FileChannel.open(
                        directory.resolve("owner.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        NOFOLLOW_LINKS,
                    )
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        null
                    } catch (failure: java.io.IOException) {
                        channel.close()
                        throw failure
                    }
                if (lock == null) {
                    channel.close()
                    return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
                }
                if (
                    HostedEndpointReclamation.prepare(directory, root, lock, observer, advertisement)
                        is Refinement.Rejected
                ) {
                    lock.release()
                    channel.close()
                    return Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
                }
                val server =
                    try {
                        ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    } catch (_: Exception) {
                        lock.release()
                        channel.close()
                        return Refinement.Rejected(HostedEndpointFailure.SOCKET_UNAVAILABLE)
                    }
                try {
                    server.bind(
                        UnixDomainSocketAddress.of(socket),
                        limits[ReadLimitParameter.HOST_ACCEPT_BACKLOG].value,
                    )
                    val socketKey = key(socket)
                    try {
                        Files.writeString(
                            descriptor,
                            Json { encodeDefaults = true }
                                .encodeToString(
                                    HostedEndpointDescriptorDocument.create(
                                        root,
                                        socket,
                                        ProcessHandle.current().pid(),
                                        host.value.toString(),
                                        advertisement,
                                    )
                                ),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        )
                        val descriptorKey = key(descriptor)
                        return Refinement.Refined(
                            OwnedHostedEndpoint(server, socket, socketKey, descriptor, descriptorKey, lock, channel)
                        )
                    } catch (failure: Exception) {
                        if (key(socket) == socketKey) Files.deleteIfExists(socket)
                        throw failure
                    }
                } catch (_: Exception) {
                    server.close()
                    lock.release()
                    channel.close()
                    return Refinement.Rejected(HostedEndpointFailure.SOCKET_UNAVAILABLE)
                }
            } catch (_: java.io.IOException) {
                return Refinement.Rejected(HostedEndpointFailure.IO_UNAVAILABLE)
            } catch (_: SecurityException) {
                return Refinement.Rejected(HostedEndpointFailure.DIRECTORY_REJECTED)
            }
        }

        private fun key(path: Path): Any =
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                ?: throw java.io.IOException("File identity unavailable")
    }
}

internal enum class HostedEndpointAdvertisement {
    SEMANTIC,
    LIFECYCLE,
}

@Serializable
internal data class HostedEndpointDescriptorDocument(
    val type: String,
    val protocol: Int,
    val root: String,
    val socket: String,
    val hostPid: Long,
    val host: String,
    val querySchema: String,
    val operations: List<String>,
) {
    companion object {
        fun create(
            root: CanonicalWorkspaceRoot,
            socket: Path,
            pid: Long,
            host: String,
            advertisement: HostedEndpointAdvertisement,
        ) =
            HostedEndpointDescriptorDocument(
                "KAST_IDE_ENDPOINT",
                HostedEndpointCapabilities.protocol,
                root.value,
                socket.toString(),
                pid,
                host,
                when (advertisement) {
                    HostedEndpointAdvertisement.SEMANTIC -> HostedReadCapabilities.querySchema
                    HostedEndpointAdvertisement.LIFECYCLE -> "kast.workspace.lifecycle.v2"
                },
                when (advertisement) {
                    HostedEndpointAdvertisement.SEMANTIC -> HostedEndpointCapabilities.operations
                    HostedEndpointAdvertisement.LIFECYCLE -> listOf("WORKSPACE_LIFECYCLE")
                },
            )
    }
}
