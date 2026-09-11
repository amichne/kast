package io.github.amichne.kast.cli.ide

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliProjectionCompletion
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

internal class ExistingIdeDescriptor internal constructor(val hostPid: Long, val host: java.util.UUID)

/** Schema authority is retained before response data can become process output. */
internal object ExistingIdeDocuments {
    private const val HOSTED_PROTOCOL_VERSION = 3
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
                if (node.path("protocol").asInt() != HOSTED_PROTOCOL_VERSION)
                    Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
                else if (
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

    private class ResponseContext(val root: CanonicalRoot, val descriptor: ExistingIdeDescriptor)

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
            return responseRejected()
        } catch (_: java.io.IOException) {
            return responseRejected()
        }
        val context = ResponseContext(root, descriptor)
        val host = read(raw, "hosted-endpoint.schema.json")
        if (host is Refinement.Refined) return hostResponse(host.value, operation, context)
        return when (operation) {
            ExistingIdeOperation.Status -> responseRejected()
            is ExistingIdeOperation.ApprovalPreparation -> preparationResponse(raw, operation, context)
            is ExistingIdeOperation.Change -> changeResponse(raw, operation, context)
            is ExistingIdeOperation.Read -> readResponse(raw, operation, context)
            is ExistingIdeOperation.Classes,
            is ExistingIdeOperation.Supertype -> legacyResponse(raw, operation, root)
        }
    }

    private fun hostResponse(
        node: tools.jackson.databind.JsonNode,
        operation: ExistingIdeOperation,
        context: ResponseContext,
    ): ExistingIdeExchange {
        if (node.path("type").asString() == "HOST_REJECTED") return hostRejected(node.toString())
        if (operation != ExistingIdeOperation.Status || node.path("type").asString() != "KAST_IDE_HOST")
            return responseRejected()
        return if (
            node.path("root").asString() == context.root.path.toString() &&
                node.path("hostPid").asLong() == context.descriptor.hostPid &&
                node.path("host").asString() == context.descriptor.host.toString()
        )
            received(node.toString())
        else responseRejected()
    }

    private fun preparationResponse(
        raw: ByteArray,
        operation: ExistingIdeOperation.ApprovalPreparation,
        context: ResponseContext,
    ): ExistingIdeExchange =
        when (
            val admitted =
                admitHostedApprovalChallenge(
                    raw = raw,
                    root = context.root,
                    descriptor = context.descriptor,
                    operation = operation,
                )
        ) {
            is Refinement.Refined -> received(admitted.value)
            is Refinement.Rejected -> operationRejection(raw, admitted.failure)
        }

    private fun changeResponse(
        raw: ByteArray,
        operation: ExistingIdeOperation.Change,
        context: ResponseContext,
    ): ExistingIdeExchange {
        val document = raw.toString(Charsets.UTF_8)
        val decoded =
            when (operation) {
                is ExistingIdeOperation.Plan -> CanonicalOperationWireBindings.changePlan.decodeOutcome(document)
                is ExistingIdeOperation.ApprovedMutation ->
                    when (operation.kind) {
                        HostedMutationOperation.CHANGE_APPLY ->
                            CanonicalOperationWireBindings.changeApply.decodeOutcome(document)
                        HostedMutationOperation.CHANGE_RECOVER ->
                            CanonicalOperationWireBindings.changeRecover.decodeOutcome(document)
                    }
            }
        val admitted =
            when (decoded) {
                is WireDecoding.Rejected -> Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)
                is WireDecoding.Decoded -> changeEvidence(decoded.value, operation, context)
            }
        return when (admitted) {
            is Refinement.Refined -> completeResponse(operation.request, document)
            is Refinement.Rejected -> operationRejection(raw, admitted.failure)
        }
    }

    private fun changeEvidence(
        outcome: OperationOutcome<*, *, *>,
        operation: ExistingIdeOperation.Change,
        context: ResponseContext,
    ) =
        admitHostedChangeOutcome(
            outcome = outcome,
            root = context.root,
            descriptor = context.descriptor,
            operation = operation,
        )

    private fun readResponse(
        raw: ByteArray,
        operation: ExistingIdeOperation.Read,
        context: ResponseContext,
    ): ExistingIdeExchange {
        val document = raw.toString(Charsets.UTF_8)
        return when (val admitted = operation.kind.admitOutcome(document, context.root, context.descriptor)) {
            is Refinement.Refined -> completeResponse(operation.request, document)
            is Refinement.Rejected -> operationRejection(raw, admitted.failure)
        }
    }

    private fun completeResponse(
        request: io.github.amichne.kast.cli.PreparedCliRequest,
        document: String,
    ): ExistingIdeExchange =
        when (val completed = request.complete(document)) {
            is CliProjectionCompletion.Completed -> ExistingIdeExchange.Semantic(completed.outcome)
            is CliProjectionCompletion.Rejected -> responseRejected()
        }

    /** Only a schema-admitted rejection can cross the boundary before operation authority exists. */
    private fun operationRejection(raw: ByteArray, failure: ExistingIdeFailure): ExistingIdeExchange {
        val rejection = read(raw, "hosted-query.schema.json")
        return if (rejection is Refinement.Refined && rejection.value.path("outcome").asString() == "rejected")
            hostRejected(rejection.value.toString())
        else ExistingIdeExchange.Rejected(failure)
    }

    private fun legacyResponse(
        raw: ByteArray,
        operation: ExistingIdeOperation,
        root: CanonicalRoot,
    ): ExistingIdeExchange {
        val node =
            when (val semantic = read(raw, "hosted-query.schema.json")) {
                is Refinement.Refined -> semantic.value
                is Refinement.Rejected -> return ExistingIdeExchange.Rejected(semantic.failure)
            }
        if (node.path("outcome").asString() == "rejected") return hostRejected(node.toString())
        if (
            node.path("stage").asString() != "RESULT_DETACHED" ||
                node.path("workspaceRoot").asString() != root.path.toString()
        )
            return responseRejected()
        val matches =
            when (operation) {
                is ExistingIdeOperation.Classes ->
                    node.path("kind").asString() == "classes" && node.path("name").asString() == operation.name.value
                is ExistingIdeOperation.Supertype ->
                    node.path("kind").asString() == "inheritors" &&
                        node.path("inheritor").path("signature").path("qualifiedIdentity").asString() ==
                            operation.name.value
                else -> false
            }
        return if (matches) received(node.toString()) else responseRejected()
    }

    private fun responseRejected() = ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED)

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
