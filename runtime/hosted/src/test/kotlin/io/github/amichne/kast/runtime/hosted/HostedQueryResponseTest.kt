package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedEvaluationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryResponseTest {
    @Test
    fun `opaque reference expansion retains a qualified prefix inside the actual wire byte limit`() {
        val items = List(40) { item() }
        val envelope =
            EvidenceEnvelope(
                CanonicalOperationWireBindings.queryRun.operation.id,
                EvidenceGeneration.parse(1).refined(),
                QueryRunResult(
                    BoundedProtocolList.create(items).refined(),
                    BoundedProtocolList.create(listOf(failure())).refined(),
                ),
            )
        val qualification =
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(items.size).refined(),
                    listOf(QueryLimitationDocument.TIME_LIMIT_REACHED),
                )
                .refined()
        val response = encodeHostedQueryResponse(OperationOutcome.Qualified(envelope, qualification))
        assertEquals(HostedEvaluationOutcome.QUALIFIED, response.outcome)
        assertTrue(
            response.document.toByteArray(Charsets.UTF_8).size <=
                ReadLimits.Default[ReadLimitParameter.HOST_RESPONSE_BYTES].value
        )
        val semantic = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val result = semantic.evidence.payload as QueryRunResult
        val retained = semantic.qualification as QueryRunQualification
        assertTrue(result.items.values.isNotEmpty())
        assertTrue(result.items.values.size < items.size)
        assertEquals(items.take(result.items.values.size), result.items.values)
        assertEquals(envelope.basis, semantic.evidence.basis)
        assertEquals(envelope.payload.failures, result.failures)
        assertEquals(40, retained.knownMinimum.value)
        assertEquals(
            listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED, QueryLimitationDocument.TIME_LIMIT_REACHED),
            retained.limitations,
        )
    }

    @Test
    fun `small complete response remains complete without projection`() {
        val envelope = envelope(listOf(item()), emptyList())
        val response =
            encodeHostedQueryResponse(OperationOutcome.Complete(envelope)) as HostedResponse.Canonical<*, *, *>
        assertEquals(OperationOutcome.Complete(envelope), response.semantic)
    }

    @Test
    fun `oversized complete response retains its proven lower bound as qualified`() {
        val response =
            encodeHostedQueryResponse(OperationOutcome.Complete(envelope(List(40) { item() }, emptyList())))
                as HostedResponse.Canonical<*, *, *>
        val outcome = response.semantic as OperationOutcome.Qualified
        val qualification = outcome.qualification as QueryRunQualification
        assertEquals(40, qualification.knownMinimum.value)
        assertEquals(listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED), qualification.limitations)
    }

    @Test
    fun `mandatory failure evidence exceeding the cap stays rejected`() {
        val response =
            encodeHostedQueryResponse(OperationOutcome.Complete(envelope(emptyList(), List(40) { failure() })))
        assertTrue(response is HostedResponse.Oversized)
        assertEquals(HostedEvaluationOutcome.REJECTED, response.outcome)
    }

    private fun failure(): QueryItemFailureDocument =
        QueryItemFailureDocument.ExactReference(
            QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v3:" + "b".repeat(3000)).refined()),
            QueryExactFailureDocument.AMBIGUOUS_DECLARATION,
        )

    private fun envelope(items: List<QueryResultItemDocument>, failures: List<QueryItemFailureDocument>) =
        EvidenceEnvelope(
            CanonicalOperationWireBindings.queryRun.operation.id,
            EvidenceGeneration.parse(1).refined(),
            QueryRunResult(BoundedProtocolList.create(items).refined(), BoundedProtocolList.create(failures).refined()),
        )

    private fun item(): QueryResultItemDocument =
        QueryResultItemDocument.ExactSymbol(
            QueryReferenceDocument.ExactSymbol(ProtocolText.parse("exact:v3:" + "a".repeat(3000)).refined()),
            SymbolKindDocument.CLASSLIKE,
            null,
            null,
            null,
            BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.RelationFactDocument>())
                .refined(),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
