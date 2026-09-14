package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.cli.projection.canonicalRejectedDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadRejectionSchemaParityTest {
    private val schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    @Test
    fun `every source rejection survives wire and installed envelope with unknown reasons rejected`() {
        SourceReadRejection.entries.forEach { reason ->
            verify(
                CanonicalOperationWireBindings.sourceRead,
                reason,
                reason.name.lowercase().replace('_', '-'),
                CanonicalSourceReadCliDocuments::project,
            )
        }
    }

    @Test
    fun `every relation rejection survives wire and installed envelope with unknown reasons rejected`() {
        RelationReadRejection.entries.forEach { reason ->
            verify(
                CanonicalOperationWireBindings.relationRead,
                reason,
                reason.name.lowercase().replace('_', '-'),
                CanonicalReadCliDocuments::projectRelation,
            )
        }
    }

    @Test
    fun `every traversal rejection survives wire and installed envelope with unknown reasons rejected`() {
        TraversalRunRejection.entries.forEach { reason ->
            verify(
                CanonicalOperationWireBindings.traversalRun,
                reason,
                reason.name.lowercase().replace('_', '-'),
                CanonicalReadCliDocuments::projectTraversal,
            )
        }
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection,
    > verify(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        reason: Rejection,
        expectedReason: String,
        project: (OperationOutcome<Result, Qualification, Rejection>) -> ProjectedCliOutcome,
    ) where Rejection : OperationRejection {
        val outcome = OperationOutcome.Rejected(reason)
        val wire = binding.encodeOutcome(outcome) as WireEncoding.Encoded
        assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(wire.document))
        val projected = project(outcome) as ProjectedCliOutcome.Rejected
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        assertEquals(setOf("operation", "status", "reason"), document.keys)
        assertEquals(binding.operation.id.value, document.getValue("operation").jsonPrimitive.content)
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals(expectedReason, document.getValue("reason").jsonPrimitive.content)
        assertTrue(validate(binding.operation, document).isEmpty(), "$reason must retain its installed schema proof")

        // Deliberately incompatible reason; every other field is emitted by the canonical projection owner.
        val unknown = canonicalRejectedDocument(binding.operation, "unclassified-test-reason")
        assertTrue(
            validate(binding.operation, Json.parseToJsonElement(unknown.value).jsonObject).isNotEmpty(),
            "${binding.operation} must reject an unknown finite reason",
        )
    }

    private fun validate(operation: CanonicalOperation, document: JsonObject) =
        schemas
            .getSchema(installedServerOutputSchema(operation).toString())
            .validate(
                Json.encodeToString(CompletedProviderEnvelope(ProviderStatus.COMPLETED, document)),
                InputFormat.JSON,
            )

    /** The provider envelope owns a contract-defined opaque canonical CLI document. */
    @Serializable private data class CompletedProviderEnvelope(val status: ProviderStatus, val document: JsonObject)

    @Serializable
    private enum class ProviderStatus {
        @SerialName("completed") COMPLETED
    }
}
