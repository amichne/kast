package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.evidenceBasis
import io.github.amichne.kast.query.protocol.protocolDocument
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryOccurrenceResponseTest {
    @Test
    fun `occurrence pages retain repeated callsites and attributed terminal omissions`() = runTest {
        val fixture = RelationPagingFixture.live()
        val request =
            RelationRequest.start(
                fixture.selector,
                RelationMeaning.References,
                fixture.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val read = fixture.operations.read(request) as RelationReadResult.Qualified
        val facts = read.batch.facts.map { it.protocolDocument(fixture.references)!! }
        val items = facts.map { fact ->
            QueryResultItemDocument.Occurrence(QueryReferenceDocument.ExactSymbol(fact.target.selector), fact)
        }
        val omission = omission(facts)
        var pending: HostedQueryOutcome = terminalOccurrences(fixture, items, omission)
        val observed = mutableListOf<QueryResultItemDocument>()
        repeat(items.size) { pageIndex ->
            var remainder: HostedQueryOutcome? = null
            val response =
                encodeHostedQueryResponse(
                    pending,
                    maximumResults = io.github.amichne.kast.kernel.ResultLimit.parse(1).refined(),
                ) { suffix ->
                    remainder = suffix
                    HostedOutputRetention.Retained(
                        ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                            .refined()
                    )
                }
                    as HostedResponse.Canonical<*, *, *>
            val page = response.semantic as OperationOutcome.Qualified
            val result = page.evidence.payload as QueryRunResult
            observed += result.items.values
            assertEquals(listOf(omission), result.omissions.values)
            assertTrue(
                QueryLimitationDocument.RELATION_INCOMPLETE in (page.qualification as QueryRunQualification).limitations
            )
            assertTrue(
                response.document.toByteArray().size <= ReadLimits.Default[ReadLimitParameter.HOST_RESPONSE_BYTES].value
            )
            assertEquals(pageIndex < items.lastIndex, remainder != null)
            pending = remainder ?: pending
        }
        assertEquals(items, observed)
        assertEquals(listOf(10, 12, 14), facts.map { it.occurrence.range.startInclusive.value })
        assertEquals(1, facts.map { it.source.selector to it.target.selector }.distinct().size)
        assertEquals(1, facts.map { it.provenance }.distinct().size)
    }

    private fun omission(facts: List<RelationFactDocument>): QueryRelationOmissionDocument =
        QueryRelationOmissionDocument(
            QueryReferenceDocument.ExactSymbol(facts.first().source.selector),
            RelationKindDocument.REFERENCES,
            RelationOmissionDocument.create(
                    RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                    RelationLimitationDocument.PROVIDER_FAILURE,
                    RelationOmissionMeasurementDocument.UnmeasuredOnPage,
                    BoundedProtocolList.create(
                            emptyList<io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument>()
                        )
                        .refined(),
                )
                .refined(),
        )

    private fun terminalOccurrences(
        fixture: RelationPagingFixture,
        items: List<QueryResultItemDocument>,
        omission: QueryRelationOmissionDocument,
    ): HostedQueryOutcome =
        OperationOutcome.Qualified(
            EvidenceEnvelope(
                CanonicalOperationWireBindings.queryRun.operation.id,
                fixture.authority.evidenceBasis(),
                QueryRunResult(
                    BoundedProtocolList.create(items).refined(),
                    BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).refined(),
                    BoundedProtocolList.create(listOf(omission)).refined(),
                ),
            ),
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(items.size).refined(),
                    listOf(QueryLimitationDocument.RELATION_INCOMPLETE),
                    io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.TerminalIncomplete(
                        io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                    ),
                )
                .refined(),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
