package io.github.amichne.kast.cli.ide

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.*
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
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
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

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
                                else ExistingIdeDocuments.response(bytes, root, operation, admitted)
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

internal class ExistingIdeDescriptor internal constructor(val hostPid: Long, val host: java.util.UUID)

/** Schema authority is retained before response data can become process output. */
internal object ExistingIdeDocuments {
    private val mapper =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    private fun read(raw: ByteArray, schema: String): Refinement<tools.jackson.databind.JsonNode, ExistingIdeFailure> {
        return try {
            val resource =
                ExistingIdeDocuments::class.java.getResourceAsStream("/ide-hosted/$schema")
                    ?: return Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
            val schemaText = resource.bufferedReader().use { it.readText() }
            val node = mapper.readTree(raw)
            if (registry.getSchema(schemaText).validate(node).isEmpty()) Refinement.Refined(node)
            else Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        } catch (_: RuntimeException) {
            Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        } catch (_: java.io.IOException) {
            Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
        }
    }

    fun descriptor(
        raw: ByteArray,
        root: CanonicalRoot,
        socket: Path,
    ): Refinement<ExistingIdeDescriptor, ExistingIdeFailure> =
        when (val read = read(raw, "hosted-endpoint.schema.json")) {
            is Refinement.Rejected -> Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            is Refinement.Refined -> {
                val node = read.value
                if (
                    node.path("type").asString() == "KAST_IDE_ENDPOINT" &&
                        node.path("root").asString() == root.path.toString() &&
                        node.path("socket").asString() == socket.toString()
                )
                    Refinement.Refined(
                        ExistingIdeDescriptor(
                            node.path("hostPid").asLong(),
                            java.util.UUID.fromString(node.path("host").asString()),
                        )
                    )
                else Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            }
        }

    fun response(
        raw: ByteArray,
        root: CanonicalRoot,
        operation: ExistingIdeOperation,
        descriptor: ExistingIdeDescriptor,
    ): ExistingIdeExchange {
        // Duplicate fields and trailing documents fail before either schema or operation decoding.
        try {
            mapper.readTree(raw)
        } catch (_: RuntimeException) {
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        } catch (_: java.io.IOException) {
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        }
        val host = read(raw, "hosted-endpoint.schema.json")
        if (host is Refinement.Refined) {
            val node = host.value
            if (node.path("type").asString() == "HOST_REJECTED") return hostRejected(node.toString())
            if (
                operation == ExistingIdeOperation.Status &&
                    node.path("type").asString() == "KAST_IDE_HOST" &&
                    node.path("root").asString() == root.path.toString() &&
                    node.path("hostPid").asLong() == descriptor.hostPid &&
                    node.path("host").asString() == descriptor.host.toString()
            )
                return received(node.toString())
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        }
        if (operation == ExistingIdeOperation.Status)
            return ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        if (operation is ExistingIdeOperation.Read) {
            val document = raw.toString(Charsets.UTF_8)
            when (val admitted = operation.kind.admitOutcome(document, root, descriptor)) {
                is Refinement.Rejected -> {
                    // Admission can fail before operation authority exists; only typed host rejection may fall back.
                    val rejection = read(raw, "hosted-query.schema.json")
                    return if (
                        rejection is Refinement.Refined && rejection.value.path("outcome").asString() == "rejected"
                    ) {
                        hostRejected(rejection.value.toString())
                    } else ExistingIdeExchange.Rejected(admitted.failure)
                }
                is Refinement.Refined -> Unit
            }
            return when (val completed = operation.request.complete(document)) {
                is CliProjectionCompletion.Completed -> ExistingIdeExchange.Semantic(completed.outcome)
                is CliProjectionCompletion.Rejected ->
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
            }
        }
        return when (val semantic = read(raw, "hosted-query.schema.json")) {
            is Refinement.Rejected -> ExistingIdeExchange.Rejected(semantic.failure)
            is Refinement.Refined -> {
                val node = semantic.value
                when {
                    node.path("outcome").asString() == "rejected" -> hostRejected(node.toString())
                    operation is ExistingIdeOperation.Classes &&
                        node.path("kind").asString() == "classes" &&
                        node.path("stage").asString() == "RESULT_DETACHED" &&
                        node.path("name").asString() == operation.name.value &&
                        node.path("workspaceRoot").asString() == root.path.toString() -> received(node.toString())
                    operation is ExistingIdeOperation.Supertype &&
                        node.path("kind").asString() == "inheritors" &&
                        node.path("stage").asString() == "RESULT_DETACHED" &&
                        node.path("workspaceRoot").asString() == root.path.toString() &&
                        node.path("inheritor").path("signature").path("qualifiedIdentity").asString() ==
                            operation.name.value -> received(node.toString())
                    else -> ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
                }
            }
        }
    }

    private fun received(raw: String): ExistingIdeExchange.Received =
        ExistingIdeExchange.Received(
            CliJsonDocument.generated(JsonElement.serializer()).create(Json.parseToJsonElement(raw))
        )

    private fun hostRejected(raw: String) =
        ExistingIdeExchange.HostRejected(
            CliJsonDocument.generated(JsonElement.serializer()).create(Json.parseToJsonElement(raw))
        )
}

/** The concrete operation decoder proves shape before its result can carry host evidence. */
internal fun ExistingIdeReadOperation.admitOutcome(
    raw: String,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
): Refinement<Unit, ExistingIdeFailure> {
    val decoded =
        when (this) {
            ExistingIdeReadOperation.QUERY_RUN -> CanonicalOperationWireBindings.queryRun.decodeOutcome(raw)
            ExistingIdeReadOperation.SYMBOL_DISCOVER -> CanonicalOperationWireBindings.symbolDiscover.decodeOutcome(raw)
            ExistingIdeReadOperation.SYMBOL_INSPECT -> CanonicalOperationWireBindings.symbolInspect.decodeOutcome(raw)
            ExistingIdeReadOperation.SOURCE_READ -> CanonicalOperationWireBindings.sourceRead.decodeOutcome(raw)
            ExistingIdeReadOperation.RELATION_READ -> CanonicalOperationWireBindings.relationRead.decodeOutcome(raw)
            ExistingIdeReadOperation.TRAVERSAL_RUN -> CanonicalOperationWireBindings.traversalRun.decodeOutcome(raw)
            ExistingIdeReadOperation.DIAGNOSTIC_CHECK ->
                CanonicalOperationWireBindings.diagnosticCheck.decodeOutcome(raw)
        }
    return when (decoded) {
        is WireDecoding.Rejected -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        is WireDecoding.Decoded ->
            when (val outcome = decoded.value) {
                is OperationOutcome.Complete -> admitLiveEvidence(outcome.evidence.basis, root, descriptor)
                is OperationOutcome.Qualified -> admitLiveEvidence(outcome.evidence.basis, root, descriptor)
                is OperationOutcome.Rejected -> Refinement.Refined(Unit)
            }
    }
}

/** A well-formed wire outcome still must belong to this exact admitted live host. */
internal fun admitLiveEvidence(
    basis: EvidenceBasis,
    root: CanonicalRoot,
    descriptor: ExistingIdeDescriptor,
): Refinement<Unit, ExistingIdeFailure> =
    when (basis) {
        is EvidenceBasis.Published -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
        is EvidenceBasis.Live ->
            if (basis.evidence.workspaceRoot == root.path.toString() && basis.evidence.host == descriptor.host) {
                Refinement.Refined(Unit)
            } else Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
    }
