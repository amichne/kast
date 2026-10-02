package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.appserver.runtime.PreparedWorkspace
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Read-only descriptor admission and one exact-socket exchange. No runtime startup dependency. */
class ExistingIdeSocketClient(
    private val home: Path,
    private val limits: ReadLimits = ReadLimits.Default,
    private val exchangeMillis: Long = limits[ReadLimitParameter.CLIENT_EXCHANGE_MILLIS].value.toLong(),
) : ExistingIdeClient {
    private val observations = java.util.concurrent.ConcurrentHashMap<CanonicalRoot, HostedServiceObservation>()

    internal fun latestObservation(root: CanonicalRoot): HostedServiceObservation =
        observations[root] ?: HostedServiceObservation.Unavailable(root, ExistingIdeFailure.HOST_UNAVAILABLE)

    /** A bounded fresh observation of known hosts; this never starts IDEA or prepares a workspace. */
    fun observedHosts(): List<HostedServiceObservation> {
        val roots = observations.keys.toList().sortedBy { it.path.toString() }
        val deadline = System.nanoTime() + 750_000_000L
        return roots.map { root ->
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining < 1) HostedServiceObservation.Unavailable(root, ExistingIdeFailure.DEADLINE_EXCEEDED)
            else {
                val client = ExistingIdeSocketClient(home, limits, minOf(500, remaining))
                client.query(root, ExistingIdeOperation.Status)
                client.observations[root]
                    ?: HostedServiceObservation.Unavailable(root, ExistingIdeFailure.HOST_UNAVAILABLE)
            }
        }
    }

    override fun query(root: CanonicalRoot, operation: ExistingIdeOperation): ExistingIdeExchange =
        query(ExistingIdeTarget.Discovered(root), operation)

    internal fun queryPrepared(workspace: PreparedWorkspace, operation: ExistingIdeOperation): ExistingIdeExchange =
        query(ExistingIdeTarget.Prepared(workspace), operation)

    private fun query(target: ExistingIdeTarget, operation: ExistingIdeOperation): ExistingIdeExchange {
        val answer =
            try {
                val root = target.root
                val digest =
                    MessageDigest.getInstance("SHA-256")
                        .digest(root.path.toString().toByteArray(Charsets.UTF_8))
                        .take(16)
                        .joinToString("") { "%02x".format(it) }
                val directory = home.resolve(".kast/ide-hosted/$digest")
                val socket = directory.resolve("host.sock")
                when (val descriptor = descriptor(directory, root, socket)) {
                    is Refinement.Refined -> exchange(target, operation, descriptor.value, socket)
                    is Refinement.Rejected -> ExistingIdeExchange.Rejected(descriptor.failure)
                }
            } catch (_: java.io.IOException) {
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
            } catch (_: SecurityException) {
                ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            } catch (_: UnsupportedOperationException) {
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
            }
        if (answer is ExistingIdeExchange.Rejected && answer.failure != ExistingIdeFailure.COMPATIBILITY_REJECTED)
            observations[target.root] = HostedServiceObservation.Unavailable(target.root, answer.failure)
        return answer
    }

    private fun exchange(
        target: ExistingIdeTarget,
        operation: ExistingIdeOperation,
        descriptor: ExistingIdeDescriptor,
        socket: Path,
    ): ExistingIdeExchange {
        if (target is ExistingIdeTarget.Prepared && descriptor.host != target.workspace.project)
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        val incarnation =
            HostedSocketIncarnation(
                Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                    ?: return ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            )
        val described = exchange(target.root, ExistingIdeOperation.Status, descriptor, socket, incarnation)
        if (described !is ExistingIdeExchange.Received) return described
        val metadata =
            when (val parsed = ExistingIdeDocuments.compatibility(described.document.value)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return ExistingIdeExchange.Rejected(parsed.failure)
            }
        val policy =
            when (val defined = requiredHostedCompatibilityPolicy()) {
                is Refinement.Refined -> defined.value
                is Refinement.Rejected -> return ExistingIdeExchange.Rejected(defined.failure)
            }
        when (val admission = policy.admit(metadata)) {
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission.Admitted ->
                observations[target.root] =
                    HostedServiceObservation.Compatible(target.root, descriptor, admission.compatibility)
            is io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission.Rejected -> {
                val version =
                    when (
                        val parsed =
                            io.github.amichne.kast.protocol.contract.HostedPluginVersion.parse(
                                metadata.hostedPluginVersion
                            )
                    ) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected ->
                            return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
                    }
                observations[target.root] =
                    HostedServiceObservation.Incompatible(
                        target.root,
                        descriptor,
                        admission.failure,
                        io.github.amichne.kast.protocol.contract.HostProvenance(version),
                    )
                return ExistingIdeExchange.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED)
            }
        }
        return if (operation == ExistingIdeOperation.Status) described
        else exchange(target.root, operation, descriptor, socket, incarnation)
    }

    private fun descriptor(
        directory: Path,
        root: CanonicalRoot,
        socket: Path,
    ): Refinement<ExistingIdeDescriptor, ExistingIdeFailure> {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return Refinement.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        if (
            directory.toRealPath() != directory ||
                Files.getOwner(directory) != Files.getOwner(home) ||
                Files.getPosixFilePermissions(directory) != PosixFilePermissions.fromString("rwx------")
        ) {
            return Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        }
        val descriptor = directory.resolve("endpoint.json")
        if (!Files.exists(descriptor, NOFOLLOW_LINKS)) return Refinement.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        if (!Files.isRegularFile(descriptor, NOFOLLOW_LINKS))
            return Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        val metadata =
            Files.newInputStream(descriptor, NOFOLLOW_LINKS).use {
                it.readNBytes(limits[ReadLimitParameter.HOST_DESCRIPTOR_BYTES].value + 1)
            }
        if (metadata.size > limits[ReadLimitParameter.HOST_DESCRIPTOR_BYTES].value) {
            return Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        }
        return ExistingIdeDocuments.descriptor(metadata, root, socket)
    }

    private fun exchange(
        root: CanonicalRoot,
        operation: ExistingIdeOperation,
        descriptor: ExistingIdeDescriptor,
        socket: Path,
        incarnation: HostedSocketIncarnation,
    ): ExistingIdeExchange {
        val attributes = Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val key = attributes.fileKey() ?: return ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        if (!attributes.isOther || attributes.isSymbolicLink || key != incarnation.key) {
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        }
        val request = operation.encodeControlRequest(root)
        if (request.size > limits[ReadLimitParameter.HOST_REQUEST_BYTES].value) {
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.REQUEST_TOO_LARGE)
        }
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            val deadline =
                WireIoDeadline(
                    channel,
                    (ElapsedTimeLimitMillis.parse(exchangeMillis) as Refinement.Refined).value,
                )
            val answer =
                try {
                    channel.connect(UnixDomainSocketAddress.of(socket))
                    if (
                        Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() != key
                    ) {
                        ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
                    } else {
                        write(channel, request)
                        response(channel, root, operation, descriptor)
                    }
                } catch (_: java.io.IOException) {
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
                } finally {
                    deadline.finish()
                }
            return if (deadline.finish() == WireRequestOutcome.TIMED_OUT) {
                ExistingIdeExchange.Rejected(ExistingIdeFailure.DEADLINE_EXCEEDED)
            } else answer
        }
    }

    private fun write(channel: SocketChannel, request: ByteArray) {
        java.io.DataOutputStream(Channels.newOutputStream(channel)).apply {
            writeInt(request.size)
            write(request)
            flush()
        }
    }

    private fun response(
        channel: SocketChannel,
        root: CanonicalRoot,
        operation: ExistingIdeOperation,
        descriptor: ExistingIdeDescriptor,
    ): ExistingIdeExchange {
        val input = java.io.DataInputStream(Channels.newInputStream(channel))
        val size = input.readInt()
        if (size !in 1..limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value) {
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        }
        val bytes = input.readNBytes(size)
        return if (bytes.size != size) ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        else ExistingIdeDocuments.response(raw = bytes, root = root, operation = operation, descriptor = descriptor)
    }
}

/** The filesystem identity admitted by the live describe exchange also fences its operation connection. */
private class HostedSocketIncarnation(val key: Any)

private sealed interface ExistingIdeTarget {
    val root: CanonicalRoot

    data class Discovered(override val root: CanonicalRoot) : ExistingIdeTarget

    data class Prepared(val workspace: PreparedWorkspace) : ExistingIdeTarget {
        override val root: CanonicalRoot
            get() = workspace.root
    }
}
