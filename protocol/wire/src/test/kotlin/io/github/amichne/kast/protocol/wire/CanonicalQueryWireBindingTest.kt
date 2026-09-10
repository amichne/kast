package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.*
import java.util.UUID
import io.github.amichne.kast.protocol.contract.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalQueryWireBindingTest {
    @Test
    fun `query request round trip admits ref only symbol output`() {
        val request = QueryRunRequest(
            from = QueryFromDocument.References(
                bounded(listOf(QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque")))),
            ),
            steps = bounded(emptyList()),
            output = QueryOutputDocument.Symbols(bounded(emptyList())),
            execution = QueryExecutionDocument(
                QueryExecutionKindDocument.EXHAUSTIVE,
                QueryExecutionBudgetDocument.INTERACTIVE,
            ),
        )

        val encoded = CanonicalOperationWireBindings.queryRun.encodeRequest(request)
        assertTrue(encoded is WireEncoding.Encoded)
        val decoded = CanonicalOperationWireBindings.queryRun.decodeRequest(
            WireRequestEnvelope.admit((encoded as WireEncoding.Encoded).document).admittedRequest(),
        )
        assertEquals(request, (decoded as WireDecoding.Decoded).value)
    }

    @Test
    fun `query request round trip retains explicit enumeration and typed stages`() {
        val request = QueryRunRequest(
            from = QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(
                        bounded(listOf(text("main"))),
                        QueryDirectoryScopeDocument(text("services/payments"), QueryContainmentDocument.DESCENDANTS),
                        QueryPackageScopeDocument(text("com.acme.payments"), QueryContainmentDocument.DESCENDANTS),
                    ),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                ),
            ),
            steps = bounded(listOf(
                QueryStepDocument.Where(
                    QueryPredicateDocument.Visibility(bounded(listOf(QueryVisibilityDocument.PUBLIC))),
                ),
                QueryStepDocument.Related(RelationKindDocument.INHERITORS),
                QueryStepDocument.Distinct,
            )),
            output = QueryOutputDocument.Symbols(
                bounded(listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION)),
            ),
            execution = QueryExecutionDocument(
                QueryExecutionKindDocument.EXHAUSTIVE,
                QueryExecutionBudgetDocument.INTERACTIVE,
            ),
        )

        val encoded = CanonicalOperationWireBindings.queryRun.encodeRequest(request)
        assertTrue(encoded is WireEncoding.Encoded)
        val decoded = CanonicalOperationWireBindings.queryRun.decodeRequest(
            WireRequestEnvelope.admit((encoded as WireEncoding.Encoded).document).admittedRequest(),
        )
        assertEquals(request, (decoded as WireDecoding.Decoded).value)
    }

    @Test
    fun `live complete evidence round trips without a published generation`() {
        val live = LiveReadEvidence.create(
            "/workspace", UUID.fromString("b41c43b0-1f11-4ca9-9ec0-b6fc88cd31c4"),
            7, LiveReadContentView.SAVED_PSI_COMMITTED, 1,
        ).refinedValue()
        val outcome = OperationOutcome.Complete(EvidenceEnvelope(
            CanonicalOperation.QUERY_RUN.id, EvidenceBasis.Live(live),
            QueryRunResult(bounded(emptyList()), bounded(emptyList())),
        ))
        val encoded = CanonicalOperationWireBindings.queryRun.encodeOutcome(outcome) as WireEncoding.Encoded
        assertTrue(!encoded.document.contains("generation"))
        assertEquals(outcome, (CanonicalOperationWireBindings.queryRun.decodeOutcome(encoded.document)
            as WireDecoding.Decoded).value)
        val ambiguous = encoded.document.replace("\"type\":\"complete\"", "\"type\":\"complete\",\"generation\":1")
        assertTrue(CanonicalOperationWireBindings.queryRun.decodeOutcome(ambiguous) is WireDecoding.Rejected)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refinedValue(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun text(raw: String): ProtocolText = when (val value = ProtocolText.parse(raw)) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error(value.failure)
    }

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        when (val value = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error(value.failure)
        }

    private fun WireRequestAdmission.admittedRequest(): AdmittedWireRequest =
        when (this) {
            is WireRequestAdmission.Admitted -> request
            is WireRequestAdmission.Rejected -> error(failure)
        }
}
