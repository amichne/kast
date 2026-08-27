package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorFailure
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorAdmission
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointPathFailure
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeProcessId
import io.github.amichne.kast.protocol.wire.metadata.IdeUnixSocketPath
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Exact-root IDE endpoint capability produced only by complete descriptor admission. */
class AdmittedIdeEndpoint internal constructor(
    val root: CanonicalRoot,
    val descriptor: IdeEndpointDescriptorV2,
) {
    internal val socketPath: Path = Path.of(descriptor.socketPath.value)
}

sealed interface IdeEndpointAdmission {
    data class Complete(val endpoint: AdmittedIdeEndpoint) : IdeEndpointAdmission
    data class Rejected(val failure: IdeEndpointAdmissionFailure) : IdeEndpointAdmission
}

sealed interface IdeEndpointAdmissionFailure {
    data class InvalidRoot(val failure: IdeEndpointPathFailure) : IdeEndpointAdmissionFailure
    data class LocationRejected(val failure: IdeEndpointPathFailure) : IdeEndpointAdmissionFailure
    data class DescriptorReadRejected(
        val failure: IdeEndpointDescriptorReadFailure,
    ) : IdeEndpointAdmissionFailure
    data class DescriptorRejected(
        val failure: IdeEndpointDescriptorFailure,
    ) : IdeEndpointAdmissionFailure
    data object RootMismatch : IdeEndpointAdmissionFailure
    data object SocketMismatch : IdeEndpointAdmissionFailure
    data object ProcessUnavailable : IdeEndpointAdmissionFailure
    data object ProcessObservationRejected : IdeEndpointAdmissionFailure
    data object EndpointUnreachable : IdeEndpointAdmissionFailure
}

sealed interface IdeEndpointDescriptorRead {
    data class Complete(val document: String) : IdeEndpointDescriptorRead
    data class Rejected(val failure: IdeEndpointDescriptorReadFailure) : IdeEndpointDescriptorRead
}

enum class IdeEndpointDescriptorReadFailure {
    UNAVAILABLE,
    NOT_REGULAR,
    TOO_LARGE,
    MALFORMED_UTF8,
}

fun interface IdeEndpointDescriptorReader {
    /**
     * Proof transition: `IdeEndpointLocation -> IdeEndpointDescriptorRead`.
     *
     * A Complete result preserves one bounded document read from only the deterministic,
     * exact-root descriptor path. Expected filesystem and UTF-8 failures remain finite
     * [IdeEndpointDescriptorReadFailure]. Raw path and bytes exist only in the reader adapter.
     */
    fun read(location: IdeEndpointLocation): IdeEndpointDescriptorRead
}

sealed interface IdeEndpointProcessObservation {
    data object Alive : IdeEndpointProcessObservation
    data object Absent : IdeEndpointProcessObservation
    data object Rejected : IdeEndpointProcessObservation
}

fun interface IdeEndpointProcessProbe {
    /**
     * Proof transition: `IdeProcessId -> IdeEndpointProcessObservation`.
     *
     * Preserves live, absent, and rejected process identity as closed states. Raw process access
     * exists only in the probe adapter.
     */
    fun observe(processId: IdeProcessId): IdeEndpointProcessObservation
}

sealed interface IdeEndpointReachability {
    data object Reachable : IdeEndpointReachability
    data object Unreachable : IdeEndpointReachability
}

fun interface IdeEndpointReachabilityProbe {
    /**
     * Proof transition: `IdeUnixSocketPath -> IdeEndpointReachability`.
     *
     * Establishes a completed native connection to the already-refined socket or finite
     * Unreachable. Raw socket access exists only in the probe adapter.
     */
    fun probe(socketPath: IdeUnixSocketPath): IdeEndpointReachability
}

/**
 * Purely ordered admission of the one deterministic IDE endpoint for an exact canonical root.
 */
class IdeEndpointAdmitter(
    private val socketDirectory: IdeEndpointSocketDirectory,
    private val compatibilityPolicy: IdeHostCompatibilityPolicy,
    private val descriptorReader: IdeEndpointDescriptorReader = FileIdeEndpointDescriptorReader,
    private val processProbe: IdeEndpointProcessProbe = JdkIdeEndpointProcessProbe,
    private val reachabilityProbe: IdeEndpointReachabilityProbe = JdkIdeEndpointReachabilityProbe,
) {
    /**
     * Proof transition: `CanonicalRoot -> IdeEndpointAdmission`.
     *
     * Establishes deterministic location, one bounded descriptor read, canonical v2 parsing,
     * exact supported compatibility and capability identity, exact root/socket identity, live IDE
     * process identity, and UDS reachability before constructing [AdmittedIdeEndpoint]. Every
     * expected rejection remains finite [IdeEndpointAdmissionFailure]. Raw descriptor bytes and
     * paths may leave only through the injected filesystem/process/socket boundaries.
     */
    fun admit(root: CanonicalRoot): IdeEndpointAdmission {
        val endpointRoot = when (val parsed = IdeEndpointCanonicalRoot.parse(root.path.toString())) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointAdmissionFailure.InvalidRoot(parsed.failure),
            )
        }
        val location = when (val located = IdeEndpointLocation.locate(
            socketDirectory,
            endpointRoot,
        )) {
            is Refinement.Refined -> located.value
            is Refinement.Rejected -> return rejected(
                IdeEndpointAdmissionFailure.LocationRejected(located.failure),
            )
        }
        val raw = when (val read = descriptorReader.read(location)) {
            is IdeEndpointDescriptorRead.Complete -> read.document
            is IdeEndpointDescriptorRead.Rejected -> return rejected(
                IdeEndpointAdmissionFailure.DescriptorReadRejected(read.failure),
            )
        }
        val descriptor = when (val admitted = IdeEndpointDescriptorV2.admit(
            raw,
            compatibilityPolicy,
        )) {
            is IdeEndpointDescriptorAdmission.Admitted -> admitted.descriptor
            is IdeEndpointDescriptorAdmission.Rejected -> return rejected(
                IdeEndpointAdmissionFailure.DescriptorRejected(admitted.failure),
            )
        }
        if (descriptor.canonicalRoot != endpointRoot) {
            return rejected(IdeEndpointAdmissionFailure.RootMismatch)
        }
        if (descriptor.socketPath != location.socketPath) {
            return rejected(IdeEndpointAdmissionFailure.SocketMismatch)
        }
        when (processProbe.observe(descriptor.processId)) {
            IdeEndpointProcessObservation.Alive -> Unit
            IdeEndpointProcessObservation.Absent -> return rejected(
                IdeEndpointAdmissionFailure.ProcessUnavailable,
            )
            IdeEndpointProcessObservation.Rejected -> return rejected(
                IdeEndpointAdmissionFailure.ProcessObservationRejected,
            )
        }
        if (reachabilityProbe.probe(descriptor.socketPath) != IdeEndpointReachability.Reachable) {
            return rejected(IdeEndpointAdmissionFailure.EndpointUnreachable)
        }
        return IdeEndpointAdmission.Complete(AdmittedIdeEndpoint(root, descriptor))
    }

    private fun rejected(failure: IdeEndpointAdmissionFailure) =
        IdeEndpointAdmission.Rejected(failure)
}

/** Filesystem adapter for one bounded, regular, non-symlinked descriptor. */
object FileIdeEndpointDescriptorReader : IdeEndpointDescriptorReader {
    override fun read(location: IdeEndpointLocation): IdeEndpointDescriptorRead {
        val path = Path.of(location.descriptorPath.value)
        val parent = path.parent ?: return readRejected(IdeEndpointDescriptorReadFailure.UNAVAILABLE)
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            return readRejected(IdeEndpointDescriptorReadFailure.NOT_REGULAR)
        }
        val bytes = try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size > MAX_IDE_ENDPOINT_DESCRIPTOR_BYTES) {
                    return readRejected(IdeEndpointDescriptorReadFailure.TOO_LARGE)
                }
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        return readRejected(IdeEndpointDescriptorReadFailure.UNAVAILABLE)
                    }
                }
                if (channel.size() != size) {
                    return readRejected(IdeEndpointDescriptorReadFailure.UNAVAILABLE)
                }
                buffer.array()
            }
        } catch (_: IOException) {
            return readRejected(IdeEndpointDescriptorReadFailure.UNAVAILABLE)
        } catch (_: SecurityException) {
            return readRejected(IdeEndpointDescriptorReadFailure.UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            return readRejected(IdeEndpointDescriptorReadFailure.NOT_REGULAR)
        }
        val document = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return readRejected(IdeEndpointDescriptorReadFailure.MALFORMED_UTF8)
        }
        return IdeEndpointDescriptorRead.Complete(document)
    }
}

object JdkIdeEndpointProcessProbe : IdeEndpointProcessProbe {
    override fun observe(processId: IdeProcessId): IdeEndpointProcessObservation = try {
        val process = ProcessHandle.of(processId.value)
        if (process.isPresent && process.get().isAlive) {
            IdeEndpointProcessObservation.Alive
        } else {
            IdeEndpointProcessObservation.Absent
        }
    } catch (_: SecurityException) {
        IdeEndpointProcessObservation.Rejected
    }
}

object JdkIdeEndpointReachabilityProbe : IdeEndpointReachabilityProbe {
    override fun probe(socketPath: IdeUnixSocketPath): IdeEndpointReachability {
        val channel = try {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        } catch (_: IOException) {
            return IdeEndpointReachability.Unreachable
        } catch (_: UnsupportedOperationException) {
            return IdeEndpointReachability.Unreachable
        }
        return channel.use { socket ->
            try {
                socket.connect(UnixDomainSocketAddress.of(Path.of(socketPath.value)))
                IdeEndpointReachability.Reachable
            } catch (_: IOException) {
                IdeEndpointReachability.Unreachable
            } catch (_: SecurityException) {
                IdeEndpointReachability.Unreachable
            }
        }
    }
}

private fun readRejected(failure: IdeEndpointDescriptorReadFailure) =
    IdeEndpointDescriptorRead.Rejected(failure)

private const val MAX_IDE_ENDPOINT_DESCRIPTOR_BYTES = 16 * 1_024L
