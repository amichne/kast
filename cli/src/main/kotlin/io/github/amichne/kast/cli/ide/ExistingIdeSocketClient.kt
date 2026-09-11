package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
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
import kotlinx.serialization.json.*

/** Read-only descriptor admission and one exact-socket exchange. No runtime startup dependency. */
class ExistingIdeSocketClient(private val home: Path, private val limits: ReadLimits = ReadLimits.Default) :
    ExistingIdeClient {
    override fun query(root: CanonicalRoot, operation: ExistingIdeOperation): ExistingIdeExchange {
        val rejected = { failure: ExistingIdeFailure -> ExistingIdeExchange.Rejected(failure) }
        try {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(root.path.toString().toByteArray(Charsets.UTF_8))
                    .take(16)
                    .joinToString("") { "%02x".format(it) }
            val directory = home.resolve(".kast/ide-hosted/$digest")
            if (!Files.exists(directory, NOFOLLOW_LINKS)) return rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
            if (
                directory.toRealPath() != directory ||
                    Files.getOwner(directory) != Files.getOwner(home) ||
                    Files.getPosixFilePermissions(directory) != PosixFilePermissions.fromString("rwx------")
            ) {
                return rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            }
            val descriptor = directory.resolve("endpoint.json")
            if (!Files.exists(descriptor, NOFOLLOW_LINKS)) return rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
            if (!Files.isRegularFile(descriptor, NOFOLLOW_LINKS))
                return rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            val metadata =
                Files.newInputStream(descriptor, NOFOLLOW_LINKS).use {
                    it.readNBytes(limits[ReadLimitParameter.HOST_DESCRIPTOR_BYTES].value + 1)
                }
            if (metadata.size > limits[ReadLimitParameter.HOST_DESCRIPTOR_BYTES].value)
                return rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            val socket = directory.resolve("host.sock")
            val admitted =
                when (val answer = ExistingIdeDocuments.descriptor(metadata, root, socket)) {
                    is Refinement.Refined -> answer.value
                    is Refinement.Rejected -> return rejected(answer.failure)
                }
            val attributes = Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val key = attributes.fileKey() ?: return rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            if (!attributes.isOther || attributes.isSymbolicLink)
                return rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            val request = buildJsonObject {
                put("root", root.path.toString())
                when (operation) {
                    ExistingIdeOperation.Status -> put("type", "DESCRIBE")
                    is ExistingIdeOperation.Classes -> {
                        put("type", "CLASS_LOOKUP")
                        put("name", operation.name.value)
                    }
                    is ExistingIdeOperation.Supertype -> {
                        put("type", "DIRECT_SUPERTYPE")
                        put("qualifiedName", operation.name.value)
                    }
                    is ExistingIdeOperation.Plan -> {
                        put("type", "CHANGE_PLAN")
                        put("document", operation.request.document)
                    }
                    is ExistingIdeOperation.ApprovalPreparation -> {
                        put("type", "CHANGE_APPROVAL_PREPARE")
                        put(
                            "document",
                            buildJsonObject {
                                put("operation", operation.kind.name)
                                put("planIdentity", operation.identity.value)
                            }
                                .toString(),
                        )
                    }
                    is ExistingIdeOperation.ApprovedMutation -> {
                        put("type", operation.kind.name)
                        put("document", operation.request.document)
                        put("approval", operation.assertion.value)
                    }
                    is ExistingIdeOperation.Read -> {
                        put("type", operation.kind.name)
                        put("document", operation.request.document)
                    }
                }
            }
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (request.size > limits[ReadLimitParameter.HOST_REQUEST_BYTES].value)
                return rejected(ExistingIdeFailure.REQUEST_TOO_LARGE)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                val deadline =
                    WireIoDeadline(
                        channel,
                        (ElapsedTimeLimitMillis.parse(limits[ReadLimitParameter.CLIENT_EXCHANGE_MILLIS].value.toLong())
                                as Refinement.Refined)
                            .value,
                    )
                val answer =
                    try {
                        channel.connect(UnixDomainSocketAddress.of(socket))
                        if (
                            Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() !=
                                key
                        ) {
                            rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
                        } else {
                            java.io.DataOutputStream(Channels.newOutputStream(channel)).apply {
                                writeInt(request.size)
                                write(request)
                                flush()
                            }
                            val input = java.io.DataInputStream(Channels.newInputStream(channel))
                            val size = input.readInt()
                            if (size !in 1..limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value)
                                rejected(ExistingIdeFailure.RESPONSE_REJECTED)
                            else {
                                val bytes = input.readNBytes(size)
                                if (bytes.size != size) rejected(ExistingIdeFailure.RESPONSE_REJECTED)
                                else
                                    ExistingIdeDocuments.response(
                                        raw = bytes,
                                        root = root,
                                        operation = operation,
                                        descriptor = admitted,
                                    )
                            }
                        }
                    } catch (_: java.io.IOException) {
                        rejected(ExistingIdeFailure.TRANSPORT_REJECTED)
                    } finally {
                        deadline.finish()
                    }
                return if (deadline.finish() == WireRequestOutcome.TIMED_OUT)
                    rejected(ExistingIdeFailure.DEADLINE_EXCEEDED)
                else answer
            }
        } catch (_: java.io.IOException) {
            return rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        } catch (_: SecurityException) {
            return rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
        } catch (_: UnsupportedOperationException) {
            return rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        }
    }
}
