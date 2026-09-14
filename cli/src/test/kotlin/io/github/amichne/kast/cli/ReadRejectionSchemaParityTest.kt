package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.cli.projection.canonicalRejectedDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.AdmittedRelationReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedSourceReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedTraversalRunRejection
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadRejectionSchemaParityTest {
    private val report = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument()))
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
            verify(
                CanonicalOperationWireBindings.sourceRead,
                AdmittedSourceReadRejection(reason, report),
                reason.name.lowercase().replace('_', '-'),
                CanonicalSourceReadCliDocuments::project,
                admitted = true,
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
            verify(
                CanonicalOperationWireBindings.relationRead,
                AdmittedRelationReadRejection(reason, report),
                reason.name.lowercase().replace('_', '-'),
                CanonicalReadCliDocuments::projectRelation,
                admitted = true,
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
            verify(
                CanonicalOperationWireBindings.traversalRun,
                AdmittedTraversalRunRejection(reason, report),
                reason.name.lowercase().replace('_', '-'),
                CanonicalReadCliDocuments::projectTraversal,
                admitted = true,
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
        admitted: Boolean = false,
    ) where Rejection : OperationRejection {
        val outcome = OperationOutcome.Rejected(reason)
        val wire = binding.encodeOutcome(outcome) as WireEncoding.Encoded
        assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(wire.document))
        val projected = project(outcome) as ProjectedCliOutcome.Rejected
        val document = Json.parseToJsonElement(projected.document.value).jsonObject
        assertEquals(
            setOf("operation", "status", "reason", "next_action") +
                if (admitted) setOf("execution_budget") else emptySet(),
            document.keys,
        )
        val decoded = binding.decodeOutcome(wire.document) as WireDecoding.Decoded
        val reprojected = project(decoded.value) as ProjectedCliOutcome.Rejected
        assertEquals(projected.document.value, reprojected.document.value)
        assertTrue("next_action" !in wire.document)
        if (admitted) assertTrue("execution_budget" in document)
        with(LiveReadOutputSchemaTest()) {
            assertRejects(binding.operation, document.with("next_action", JsonNull))
            assertRejects(binding.operation, document.with("next_action", JsonPrimitive("silently_refresh")))
            assertRejects(binding.operation, document.with("reason", JsonPrimitive("unclassified-test-reason")))
        }
        assertEquals(binding.operation.id.value, document.getValue("operation").jsonPrimitive.content)
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals(expectedReason, document.getValue("reason").jsonPrimitive.content)
        assertTrue(validate(binding.operation, document).isEmpty(), "$reason must retain its installed schema proof")

        // Deliberately incompatible reason; every other field is emitted by the canonical projection owner.
        val unknown = canonicalRejectedDocument(binding.operation, expectedReason)
        assertTrue(
            validate(binding.operation, Json.parseToJsonElement(unknown.value).jsonObject).isNotEmpty(),
            "${binding.operation} must reject a missing recovery action",
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
