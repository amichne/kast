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
    fun `source complete and qualified grants survive wire CLI and installed schemas`() {
        val evidence =
            EvidenceEnvelope(
                CanonicalOperation.SOURCE_READ.id,
                basis,
                fixture.sourceResult(basis).copy(executionBudget = report),
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
