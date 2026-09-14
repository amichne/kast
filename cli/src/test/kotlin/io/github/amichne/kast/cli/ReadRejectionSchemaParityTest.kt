package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.kernel.OperationOutcome
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadRejectionSchemaParityTest {
    private val fixture = LiveReadOutputSchemaTest()

    @Test
    fun `every source rejection survives wire and installed envelope with unknown reasons rejected`() {
        SourceReadRejection.entries.forEach { reason ->
            verify(CanonicalOperationWireBindings.sourceRead, reason, CanonicalSourceReadCliDocuments::project)
        }
    }

    @Test
    fun `every relation rejection survives wire and installed envelope with unknown reasons rejected`() {
        RelationReadRejection.entries.forEach { reason ->
            verify(CanonicalOperationWireBindings.relationRead, reason, CanonicalReadCliDocuments::projectRelation)
        }
    }

    @Test
    fun `every traversal rejection survives wire and installed envelope with unknown reasons rejected`() {
        TraversalRunRejection.entries.forEach { reason ->
            verify(CanonicalOperationWireBindings.traversalRun, reason, CanonicalReadCliDocuments::projectTraversal)
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
        project: (OperationOutcome<Result, Qualification, Rejection>) -> ProjectedCliOutcome,
    ) where Rejection : Enum<Rejection>, Rejection : OperationRejection =
        with(fixture) {
            val outcome = OperationOutcome.Rejected(reason)
            val wire = binding.encodeOutcome(outcome) as WireEncoding.Encoded
            assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(wire.document))
            val document = project(outcome).document()
            assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
            assertEquals(reason.name.lowercase().replace('_', '-'), document.getValue("reason").jsonPrimitive.content)
            assertAdmits(binding.operation, document)
            // Deliberately incompatible output proves the installed schema is closed, not merely a string shape.
            assertRejects(binding.operation, document.with("reason", JsonPrimitive("unclassified-test-reason")))
        }
}
