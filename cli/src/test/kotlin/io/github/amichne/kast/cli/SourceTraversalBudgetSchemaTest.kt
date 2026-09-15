package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceTraversalBudgetSchemaTest {
    private val fixture = LiveReadOutputSchemaTest()
    private val basis = EvidenceBasis.Published(EvidenceGeneration.parse(1).proven())
    private val report =
        ExecutionBudgetReport.from(
            hostedSchemaBudgetGrant(ExecutionBudgetDocument(maxResults = ResultLimit.parse(999).proven()))
        )

    @Test
    fun `source complete and qualified grants survive wire CLI and installed schemas`() =
        verifySource(io.github.amichne.kast.protocol.contract.SourceReadFormatDocument.EXPANDED)

    @Test
    fun `compact complete and qualified grants survive wire CLI and installed schemas`() =
        verifySource(io.github.amichne.kast.protocol.contract.SourceReadFormatDocument.COMPACT)

    @Test
    fun `compact live snapshots retain their required live schema`() =
        verifySource(
            io.github.amichne.kast.protocol.contract.SourceReadFormatDocument.COMPACT,
            EvidenceBasis.Live(
                io.github.amichne.kast.kernel.LiveReadEvidence.create(
                        "/workspace",
                        java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        7,
                        io.github.amichne.kast.kernel.LiveReadContentView.SAVED_PSI_COMMITTED,
                        1,
                    )
                    .proven()
            ),
        )

    private fun verifySource(
        format: io.github.amichne.kast.protocol.contract.SourceReadFormatDocument,
        sourceBasis: EvidenceBasis = basis,
    ) {
        val evidence =
            EvidenceEnvelope(
                CanonicalOperation.SOURCE_READ.id,
                sourceBasis,
                fixture.sourceResult(sourceBasis).copy(executionBudget = report, format = format),
            )
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCountDocument.parse(0).proven(),
                    listOf(SourceReadLimitationDocument.TIME_LIMIT_REACHED),
                    SourceQualifiedProgressDocument.TerminalIncomplete(
                        SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE
                    ),
                )
                .proven()
        verify(
            CanonicalOperationWireBindings.sourceRead,
            OperationOutcome.Complete(evidence),
            CanonicalSourceReadCliDocuments::project,
        )
        verify(
            CanonicalOperationWireBindings.sourceRead,
            OperationOutcome.Qualified(evidence, qualification),
            CanonicalSourceReadCliDocuments::project,
        )
        if (format == io.github.amichne.kast.protocol.contract.SourceReadFormatDocument.COMPACT)
            with(fixture) {
                val document = CanonicalSourceReadCliDocuments.project(OperationOutcome.Complete(evidence)).document()
                val content = document.getValue("content") as kotlinx.serialization.json.JsonArray
                assertEquals(JsonPrimitive("source"), content.first().jsonObject["type"])
                assertRejects(
                    CanonicalOperation.SOURCE_READ,
                    document.with(
                        "content",
                        kotlinx.serialization.json.Json.encodeToJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(
                                kotlinx.serialization.json.JsonElement.serializer()
                            ),
                            content.reversed(),
                        ),
                    ),
                )
                assertRejects(CanonicalOperation.SOURCE_READ, document.with("format", JsonPrimitive("invented")))
                if (sourceBasis is EvidenceBasis.Live) assertMixedSnapshotRejected(document)
            }
    }

    private fun assertMixedSnapshotRejected(document: kotlinx.serialization.json.JsonObject) =
        with(fixture) {
            val content = document.getValue("content") as kotlinx.serialization.json.JsonArray
            val structure = content[1].jsonObject
            val snapshot = structure.getValue("snapshot").jsonObject
            val mixed = structure.with("snapshot", snapshot.with("generation", JsonPrimitive(1)))
            val invalidContent =
                kotlinx.serialization.json.Json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                    listOf(content.first(), mixed),
                )
            assertRejects(CanonicalOperation.SOURCE_READ, document.with("content", invalidContent))
        }

    @Test
    fun `traversal complete and qualified grants survive wire CLI and installed schemas`() {
        val evidence =
            EvidenceEnvelope(
                CanonicalOperation.TRAVERSAL_RUN.id,
                basis,
                fixture.traversalResult().copy(executionBudget = report),
            )
        val qualification =
            TraversalRunQualification.terminalIncomplete(
                    listOf(TraversalLimitationDocument.TIME_LIMIT_REACHED),
                    emptyList(),
                )
                .proven()
        verify(
            CanonicalOperationWireBindings.traversalRun,
            OperationOutcome.Complete(evidence),
            CanonicalReadCliDocuments::projectTraversal,
        )
        verify(
            CanonicalOperationWireBindings.traversalRun,
            OperationOutcome.Qualified(evidence, qualification),
            CanonicalReadCliDocuments::projectTraversal,
        )
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > verify(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        outcome: OperationOutcome<Result, Qualification, Rejection>,
        project: (OperationOutcome<Result, Qualification, Rejection>) -> ProjectedCliOutcome,
    ) =
        with(fixture) {
            val wire = binding.encodeOutcome(outcome) as WireEncoding.Encoded
            assertEquals(WireDecoding.Decoded(outcome), binding.decodeOutcome(wire.document))
            val document = project(outcome).document()
            assertAdmits(binding.operation, document)
            val budget = document.getValue("execution_budget").jsonObject
            val results = budget.getValue("max_results").jsonObject
            assertEquals(JsonPrimitive(128), results["effective"])
            assertEquals(JsonPrimitive("caller"), results["selection"])
            assertRejects(
                binding.operation,
                document.with(
                    "execution_budget",
                    budget.with("max_results", results.with("effective", JsonPrimitive(0))),
                ),
            )
        }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
