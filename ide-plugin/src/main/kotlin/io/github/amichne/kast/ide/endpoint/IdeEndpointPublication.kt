package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorAdmission
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import java.io.IOException
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal fun interface IdeEndpointPublisher {
    fun publish(prepared: PreparedIdeEndpoint): IdeEndpointActivation
}

private sealed interface IdeEndpointOwnerState {
    data object Unpublished : IdeEndpointOwnerState
    data class Ready(val endpoint: ReadyIdeEndpoint) : IdeEndpointOwnerState
}

/** Project-scoped single-publication authority kept separate from IntelliJ construction. */
internal class IdeEndpointOwner(
    private val publisher: IdeEndpointPublisher,
) {
    private var state: IdeEndpointOwnerState = IdeEndpointOwnerState.Unpublished

    /**
     * Proof transition: `PreparedIdeEndpoint -> IdeEndpointActivation`.
     *
     * Establishes at most one ready endpoint for this Project owner. Duplicate requests reject
     * before invoking the UDS publisher; publication failures preserve unpublished state.
     */
    @Synchronized
    fun publish(prepared: PreparedIdeEndpoint): IdeEndpointActivation = when (state) {
        is IdeEndpointOwnerState.Ready -> IdeEndpointActivation.Rejected(
            IdeEndpointPublicationFailure.DUPLICATE_ENDPOINT,
        )
        IdeEndpointOwnerState.Unpublished -> when (val activation = publisher.publish(prepared)) {
            is IdeEndpointActivation.Ready -> {
                state = IdeEndpointOwnerState.Ready(activation.endpoint)
                activation
            }
            is IdeEndpointActivation.Rejected -> activation
        }
    }
}

internal enum class IdeEndpointPublicationFault {
    NONE,
    SOCKET_BIND,
    DESCRIPTOR_PUBLICATION,
}

private sealed interface EndpointPathObservation {
    data object Absent : EndpointPathObservation
    data object Directory : EndpointPathObservation
    data object NonSocket : EndpointPathObservation
    data object Socket : EndpointPathObservation
}

private sealed interface DescriptorPublication {
    data class Published(
        val descriptor: OwnedPublishedDescriptor,
    ) : DescriptorPublication

    data object Rejected : DescriptorPublication
}

private sealed interface DescriptorReadBack {
    data class Admitted(val descriptor: IdeEndpointDescriptorV2) : DescriptorReadBack
    data object Rejected : DescriptorReadBack
}

internal object JdkIdeEndpointPublisher : IdeEndpointPublisher {
    /**
     * Proof transition: `PreparedIdeEndpoint -> IdeEndpointActivation`.
     *
     * Establishes an atomically created exact-root directory, one physical UDS bind, a
     * canonically re-admitted descriptor staging file inside that exclusive namespace, and one
     * same-parent atomic move to the socket-suffix descriptor before issuing
     * [ReadyIdeEndpoint]. Every expected path, bind, write, move, or admission failure remains
     * [IdeEndpointPublicationFailure]. Raw paths and bytes leave only at this JDK boundary.
     */
    override fun publish(prepared: PreparedIdeEndpoint): IdeEndpointActivation =
        publish(prepared, IdeEndpointPublicationFault.NONE)

    /** Canonical negative-proof seam; production publication always uses `NONE`. */
    @JvmSynthetic
    internal fun publishTesting(
        prepared: PreparedIdeEndpoint,
        fault: IdeEndpointPublicationFault,
    ): IdeEndpointActivation = publish(prepared, fault)

    private fun publish(
        prepared: PreparedIdeEndpoint,
        fault: IdeEndpointPublicationFault,
    ): IdeEndpointActivation {
        val stateDirectoryPath = Path.of(prepared.location.stateDirectoryPath.value)
        when (observe(stateDirectoryPath)) {
            EndpointPathObservation.Absent -> Unit
            EndpointPathObservation.NonSocket -> return rejected(
                IdeEndpointPublicationFailure.OCCUPIED_NON_SOCKET_PATH,
            )
            EndpointPathObservation.Directory -> {
                if (observe(Path.of(prepared.location.descriptorPath.value)) !=
                    EndpointPathObservation.Absent
                ) {
                    return rejected(IdeEndpointPublicationFailure.OCCUPIED_DESCRIPTOR_PATH)
                }
                val socketPath = Path.of(prepared.location.socketPath.value)
                return rejected(
                    when (observe(socketPath)) {
                        EndpointPathObservation.NonSocket ->
                            IdeEndpointPublicationFailure.OCCUPIED_NON_SOCKET_PATH
                        else -> IdeEndpointPublicationFailure.REACHABLE_OR_OCCUPIED_SOCKET
                    },
                )
            }
            EndpointPathObservation.Socket -> return rejected(
                IdeEndpointPublicationFailure.REACHABLE_OR_OCCUPIED_SOCKET,
            )
        }
        val directory = when (val creation = OwnedEndpointDirectory.create(prepared.location)) {
            is OwnedEndpointDirectoryCreation.Created -> creation.directory
            OwnedEndpointDirectoryCreation.Rejected -> return rejected(
                IdeEndpointPublicationFailure.SOCKET_BIND_FAILED,
            )
        }
        val channel = try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: IOException) {
            directory.rollbackEmpty()
            return rejected(IdeEndpointPublicationFailure.SOCKET_BIND_FAILED)
        } catch (_: UnsupportedOperationException) {
            directory.rollbackEmpty()
            return rejected(IdeEndpointPublicationFailure.SOCKET_BIND_FAILED)
        } catch (_: SecurityException) {
            directory.rollbackEmpty()
            return rejected(IdeEndpointPublicationFailure.SOCKET_BIND_FAILED)
        }
        if (fault == IdeEndpointPublicationFault.SOCKET_BIND) {
            channel.closeQuietly()
        }
        val socket = when (val binding = directory.bindSocket(channel)) {
            is OwnedBoundSocketCreation.Bound -> binding.socket
            OwnedBoundSocketCreation.Rejected -> {
                directory.rollbackEmpty()
                return rejected(IdeEndpointPublicationFailure.SOCKET_BIND_FAILED)
            }
        }
        val encoded = prepared.descriptor.encode().document
        val descriptor = when (
            val publication = publishDescriptor(
                directory,
                encoded,
                prepared,
                fault,
            )
        ) {
            is DescriptorPublication.Published -> publication.descriptor
            DescriptorPublication.Rejected -> {
                directory.rollback(socket)
                return rejected(IdeEndpointPublicationFailure.DESCRIPTOR_PUBLICATION_FAILED)
            }
        }
        return IdeEndpointActivation.Ready(
            ReadyIdeEndpoint(
                prepared.canonicalRoot,
                prepared.descriptor,
                prepared.location,
                IdeEndpointTransport(socket, prepared.runtime),
                ReadyEndpointOwnership(directory, socket, descriptor),
            ),
        )
    }

    /**
     * Proof transition: `(OwnedEndpointDirectory, canonical descriptor String) ->
     * DescriptorPublication`.
     *
     * Establishes that the exact bytes are re-admitted while still in the exclusive directory,
     * then preserves that physical file identity through one atomic move to the public path.
     */
    private fun publishDescriptor(
        directory: OwnedEndpointDirectory,
        document: String,
        prepared: PreparedIdeEndpoint,
        fault: IdeEndpointPublicationFault,
    ): DescriptorPublication {
        val temporary = when (val creation = directory.createDescriptorTemporary()) {
            is OwnedDescriptorTemporaryCreation.Created -> creation.temporary
            OwnedDescriptorTemporaryCreation.Rejected -> return DescriptorPublication.Rejected
        }
        try {
            Files.writeString(
                temporary.path,
                document,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            if (fault == IdeEndpointPublicationFault.DESCRIPTOR_PUBLICATION) {
                temporary.delete()
                return DescriptorPublication.Rejected
            }
            val admitted = when (val readBack = readBack(temporary.path, prepared)) {
                is DescriptorReadBack.Admitted -> readBack.descriptor
                DescriptorReadBack.Rejected -> {
                    temporary.delete()
                    return DescriptorPublication.Rejected
                }
            }
            if (admitted.encode().document != document) {
                temporary.delete()
                return DescriptorPublication.Rejected
            }
            val identified = when (val observed = temporary.identify()) {
                is OwnedDescriptorTemporaryIdentity.Identified -> observed.temporary
                OwnedDescriptorTemporaryIdentity.Unavailable -> {
                    temporary.delete()
                    return DescriptorPublication.Rejected
                }
            }
            return when (val publication = identified.publishAtomically()) {
                is OwnedDescriptorAtomicPublication.Published ->
                    DescriptorPublication.Published(publication.descriptor)
                OwnedDescriptorAtomicPublication.Rejected -> DescriptorPublication.Rejected
            }
        } catch (_: IOException) {
            temporary.delete()
            return DescriptorPublication.Rejected
        } catch (_: SecurityException) {
            temporary.delete()
            return DescriptorPublication.Rejected
        }
    }

    /**
     * Proof transition: `(Path, PreparedIdeEndpoint) -> DescriptorReadBack`.
     *
     * Establishes that staged bytes decode to descriptor v2 under the prepared endpoint's exact
     * compatibility policy. Read, security, or admission failures remain
     * [DescriptorReadBack.Rejected]. Raw paths and text leave only at this publication boundary.
     */
    private fun readBack(
        path: Path,
        prepared: PreparedIdeEndpoint,
    ): DescriptorReadBack = try {
        when (val admission = IdeEndpointDescriptorV2.admit(
            Files.readString(path),
            prepared.compatibilityPolicy,
        )) {
            is IdeEndpointDescriptorAdmission.Admitted -> DescriptorReadBack.Admitted(
                admission.descriptor,
            )
            is IdeEndpointDescriptorAdmission.Rejected -> DescriptorReadBack.Rejected
        }
    } catch (_: IOException) {
        DescriptorReadBack.Rejected
    } catch (_: SecurityException) {
        DescriptorReadBack.Rejected
    }

    /**
     * Proof transition: `Path -> EndpointPathObservation`.
     *
     * Establishes a no-follow absent, directory, non-socket, or socket observation. Inaccessible
     * and ambiguous states fail closed as [EndpointPathObservation.NonSocket]. Raw attributes and
     * paths leave only at this endpoint-filesystem boundary.
     */
    private fun observe(path: Path): EndpointPathObservation = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        when {
            attributes.isDirectory -> EndpointPathObservation.Directory
            attributes.isOther -> EndpointPathObservation.Socket
            else -> EndpointPathObservation.NonSocket
        }
    } catch (_: NoSuchFileException) {
        EndpointPathObservation.Absent
    } catch (_: IOException) {
        EndpointPathObservation.NonSocket
    } catch (_: SecurityException) {
        EndpointPathObservation.NonSocket
    }

    private fun rejected(failure: IdeEndpointPublicationFailure) =
        IdeEndpointActivation.Rejected(failure)
}
