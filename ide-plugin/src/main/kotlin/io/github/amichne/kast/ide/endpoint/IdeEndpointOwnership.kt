package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

@JvmInline
private value class EndpointPhysicalFileIdentity private constructor(val value: Any) {
    companion object {
        /**
         * Proof transition: `Path -> PhysicalFileIdentity`.
         *
         * Establishes one retained no-follow physical file key, absence, or the closed
         * [PhysicalFileIdentity.Unavailable] state. Raw [Path], attributes, and file keys leave
         * only at this endpoint-filesystem boundary.
         */
        fun observe(path: Path): PhysicalFileIdentity = try {
            val raw = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ).fileKey()
            if (raw == null) {
                PhysicalFileIdentity.Unavailable
            } else {
                PhysicalFileIdentity.Identified(EndpointPhysicalFileIdentity(raw))
            }
        } catch (_: NoSuchFileException) {
            PhysicalFileIdentity.Absent
        } catch (_: IOException) {
            PhysicalFileIdentity.Unavailable
        } catch (_: SecurityException) {
            PhysicalFileIdentity.Unavailable
        }
    }
}

private sealed interface PhysicalFileIdentity {
    data class Identified(val value: EndpointPhysicalFileIdentity) : PhysicalFileIdentity
    data object Absent : PhysicalFileIdentity
    data object Unavailable : PhysicalFileIdentity
}

internal sealed interface OwnedEndpointDirectoryCreation {
    data class Created(
        val directory: OwnedEndpointDirectory,
    ) : OwnedEndpointDirectoryCreation

    data object Rejected : OwnedEndpointDirectoryCreation
}

internal sealed interface OwnedBoundSocketCreation {
    data class Bound(
        val socket: OwnedEndpointPath,
    ) : OwnedBoundSocketCreation

    data object Rejected : OwnedBoundSocketCreation
}

internal sealed interface OwnedDescriptorTemporaryCreation {
    data class Created(
        val temporary: OwnedDescriptorTemporary,
    ) : OwnedDescriptorTemporaryCreation

    data object Rejected : OwnedDescriptorTemporaryCreation
}

internal sealed interface OwnedDescriptorTemporaryIdentity {
    data class Identified(
        val temporary: IdentifiedOwnedDescriptorTemporary,
    ) : OwnedDescriptorTemporaryIdentity

    data object Unavailable : OwnedDescriptorTemporaryIdentity
}

internal sealed interface OwnedDescriptorAtomicPublication {
    data class Published(
        val descriptor: OwnedPublishedDescriptor,
    ) : OwnedDescriptorAtomicPublication

    data object Rejected : OwnedDescriptorAtomicPublication
}

/** Exclusive exact-root namespace created atomically from an absent location. */
internal class OwnedEndpointDirectory private constructor(
    private val location: IdeEndpointLocation,
    private val identity: EndpointPhysicalFileIdentity,
) {
    val path: Path get() = Path.of(location.stateDirectoryPath.value)
    private val descriptorPath: Path get() = Path.of(location.descriptorPath.value)

    /**
     * Proof transition: `ServerSocketChannel -> OwnedBoundSocketCreation`.
     *
     * Establishes a physical bind at this directory capability's derived socket path before
     * returning [OwnedEndpointPath]. I/O, unsupported-platform, and security failures remain the
     * closed [OwnedBoundSocketCreation.Rejected] state. Raw channels and paths leave only here.
     */
    fun bindSocket(channel: ServerSocketChannel): OwnedBoundSocketCreation = try {
        val socketPath = Path.of(location.socketPath.value)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        when (val observed = EndpointPhysicalFileIdentity.observe(socketPath)) {
            is PhysicalFileIdentity.Identified -> OwnedBoundSocketCreation.Bound(
                BoundSocket(channel, observed.value),
            )
            PhysicalFileIdentity.Absent,
            PhysicalFileIdentity.Unavailable,
            -> {
                channel.closeQuietly()
                OwnedBoundSocketCreation.Rejected
            }
        }
    } catch (_: IOException) {
        channel.closeQuietly()
        OwnedBoundSocketCreation.Rejected
    } catch (_: UnsupportedOperationException) {
        channel.closeQuietly()
        OwnedBoundSocketCreation.Rejected
    } catch (_: SecurityException) {
        channel.closeQuietly()
        OwnedBoundSocketCreation.Rejected
    }

    /**
     * Proof transition: `OwnedEndpointDirectory -> OwnedDescriptorTemporaryCreation`.
     *
     * Establishes a newly created temporary whose parent is the descriptor's exclusive parent.
     * Creation failures remain [OwnedDescriptorTemporaryCreation.Rejected]. Raw [Path] extraction
     * is permitted only by the descriptor publication boundary.
     */
    fun createDescriptorTemporary(): OwnedDescriptorTemporaryCreation = try {
        OwnedDescriptorTemporaryCreation.Created(
            DescriptorTemporary(Files.createTempFile(path, ".descriptor-", ".tmp")),
        )
    } catch (_: IOException) {
        OwnedDescriptorTemporaryCreation.Rejected
    } catch (_: SecurityException) {
        OwnedDescriptorTemporaryCreation.Rejected
    }

    /** Closes and removes the owned socket namespace after a pre-READY failure. */
    fun rollback(socket: OwnedEndpointPath) {
        socket.close()
        socket.deleteFromOwner()
        deleteIfStillExclusive()
    }

    /** Removes an empty namespace after failure before bind. */
    fun rollbackEmpty() = deleteIfStillExclusive()

    internal fun retireIfStillExclusive(): EndpointArtifactRetirement =
        deleteRetainedPath(path, identity)

    private fun deleteIfStillExclusive() {
        retireIfStillExclusive()
    }

    private fun deleteRetainedPath(
        retainedPath: Path,
        retainedIdentity: EndpointPhysicalFileIdentity,
    ): EndpointArtifactRetirement = when (
        val observed = EndpointPhysicalFileIdentity.observe(retainedPath)
    ) {
        PhysicalFileIdentity.Absent -> EndpointArtifactRetirement.ALREADY_ABSENT
        PhysicalFileIdentity.Unavailable -> EndpointArtifactRetirement.IDENTITY_UNAVAILABLE
        is PhysicalFileIdentity.Identified -> if (observed.value != retainedIdentity) {
            EndpointArtifactRetirement.IDENTITY_MISMATCH
        } else try {
            Files.delete(retainedPath)
            EndpointArtifactRetirement.REMOVED
        } catch (_: NoSuchFileException) {
            EndpointArtifactRetirement.ALREADY_ABSENT
        } catch (_: IOException) {
            EndpointArtifactRetirement.DELETE_FAILED
        } catch (_: SecurityException) {
            EndpointArtifactRetirement.DELETE_FAILED
        }
    }

    /** Socket authority whose path can only be derived from this directory's location. */
    private inner class BoundSocket(
        private val channel: ServerSocketChannel,
        private val identity: EndpointPhysicalFileIdentity,
    ) : OwnedEndpointPath {
        override fun accept(): IdeEndpointSocketAcceptance = try {
            IdeEndpointSocketAcceptance.Accepted(channel.accept())
        } catch (_: IOException) {
            IdeEndpointSocketAcceptance.Rejected
        } catch (_: SecurityException) {
            IdeEndpointSocketAcceptance.Rejected
        }

        override fun close() = channel.closeQuietly()

        override fun deleteFromOwner(): EndpointArtifactRetirement = deleteRetainedPath(
            Path.of(location.socketPath.value),
            identity,
        )
    }

    /** Staging authority whose path comes only from createTempFile inside this directory. */
    private inner class DescriptorTemporary(
        override val path: Path,
    ) : OwnedDescriptorTemporary {
        override fun delete() {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
        }

        /**
         * Proof transition: `OwnedDescriptorTemporary -> OwnedDescriptorTemporaryIdentity`.
         *
         * Retains the staged file's physical identity before its atomic move. An unavailable file
         * key remains [OwnedDescriptorTemporaryIdentity.Unavailable]; raw keys do not escape.
         */
        override fun identify(): OwnedDescriptorTemporaryIdentity =
            when (val observed = EndpointPhysicalFileIdentity.observe(path)) {
                is PhysicalFileIdentity.Identified -> OwnedDescriptorTemporaryIdentity.Identified(
                    IdentifiedDescriptorTemporary(this, observed.value),
                )
                PhysicalFileIdentity.Absent,
                PhysicalFileIdentity.Unavailable,
                -> OwnedDescriptorTemporaryIdentity.Unavailable
            }
    }

    private inner class IdentifiedDescriptorTemporary(
        private val temporary: DescriptorTemporary,
        private val identity: EndpointPhysicalFileIdentity,
    ) : IdentifiedOwnedDescriptorTemporary {
        override val path: Path get() = temporary.path

        /**
         * Proof transition: `IdentifiedOwnedDescriptorTemporary ->
         * OwnedDescriptorAtomicPublication`.
         *
         * Preserves the retained physical identity through one same-parent `ATOMIC_MOVE` and
         * issues [OwnedPublishedDescriptor] only after success. Unsupported or failed moves remain
         * [OwnedDescriptorAtomicPublication.Rejected], delete the owned temporary, and never fall
         * back. Raw paths leave only at this endpoint-filesystem boundary.
         */
        override fun publishAtomically(): OwnedDescriptorAtomicPublication = try {
            Files.move(path, descriptorPath, StandardCopyOption.ATOMIC_MOVE)
            OwnedDescriptorAtomicPublication.Published(
                RetainedPublishedDescriptor(descriptorPath, identity),
            )
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.delete()
            OwnedDescriptorAtomicPublication.Rejected
        } catch (_: IOException) {
            temporary.delete()
            OwnedDescriptorAtomicPublication.Rejected
        } catch (_: SecurityException) {
            temporary.delete()
            OwnedDescriptorAtomicPublication.Rejected
        }
    }

    companion object {
        /**
         * Proof transition: `IdeEndpointLocation -> OwnedEndpointDirectoryCreation`.
         *
         * Establishes atomic creation, 0700 permissions, and retained physical identity for the
         * exact-root namespace. Creation or identity failures remain the closed
         * [OwnedEndpointDirectoryCreation.Rejected] state. Raw paths leave only at this endpoint
         * filesystem boundary.
         */
        fun create(
            location: IdeEndpointLocation,
        ): OwnedEndpointDirectoryCreation {
            val path = Path.of(location.stateDirectoryPath.value)
            try {
                Files.createDirectory(
                    path,
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------"),
                    ),
                )
            } catch (_: IOException) {
                return OwnedEndpointDirectoryCreation.Rejected
            } catch (_: SecurityException) {
                return OwnedEndpointDirectoryCreation.Rejected
            }
            return when (val observed = EndpointPhysicalFileIdentity.observe(path)) {
                is PhysicalFileIdentity.Identified -> OwnedEndpointDirectoryCreation.Created(
                    OwnedEndpointDirectory(location, observed.value),
                )
                PhysicalFileIdentity.Absent,
                PhysicalFileIdentity.Unavailable,
                -> {
                    try {
                        Files.delete(path)
                    } catch (_: IOException) {
                    } catch (_: SecurityException) {
                    }
                    OwnedEndpointDirectoryCreation.Rejected
                }
            }
        }
    }
}

internal interface OwnedEndpointPath {
    fun accept(): IdeEndpointSocketAcceptance
    fun close()
    fun deleteFromOwner(): EndpointArtifactRetirement
}

internal interface OwnedDescriptorTemporary {
    val path: Path
    fun delete()
    fun identify(): OwnedDescriptorTemporaryIdentity
}

internal interface IdentifiedOwnedDescriptorTemporary {
    val path: Path
    fun publishAtomically(): OwnedDescriptorAtomicPublication
}

/** Retained descriptor ownership for the endpoint lifecycle.s later READY retirement. */
internal sealed interface OwnedPublishedDescriptor {
    val path: Path
    fun deleteFromOwner(): EndpointArtifactRetirement
}

private class RetainedPublishedDescriptor(
    override val path: Path,
    private val identity: EndpointPhysicalFileIdentity,
) : OwnedPublishedDescriptor {
    override fun deleteFromOwner(): EndpointArtifactRetirement = when (
        val observed = EndpointPhysicalFileIdentity.observe(path)
    ) {
        PhysicalFileIdentity.Absent -> EndpointArtifactRetirement.ALREADY_ABSENT
        PhysicalFileIdentity.Unavailable -> EndpointArtifactRetirement.IDENTITY_UNAVAILABLE
        is PhysicalFileIdentity.Identified -> if (observed.value != identity) {
            EndpointArtifactRetirement.IDENTITY_MISMATCH
        } else try {
            Files.delete(path)
            EndpointArtifactRetirement.REMOVED
        } catch (_: NoSuchFileException) {
            EndpointArtifactRetirement.ALREADY_ABSENT
        } catch (_: IOException) {
            EndpointArtifactRetirement.DELETE_FAILED
        } catch (_: SecurityException) {
            EndpointArtifactRetirement.DELETE_FAILED
        }
    }
}

internal fun ServerSocketChannel.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
