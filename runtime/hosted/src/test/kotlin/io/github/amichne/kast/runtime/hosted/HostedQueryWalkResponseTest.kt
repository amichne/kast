package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.evidenceBasis
import io.github.amichne.kast.query.protocol.protocolDocument
import io.github.amichne.kast.relation.contract.RelationReadResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryWalkResponseTest {
    @Test
    fun `query walk pages retain occurrence records and depth frontier progress and incomplete coverage`() = runTest {
        val (records, observation, outcome) = walkFixture()
        var pending = outcome
        val observed = mutableListOf<QueryResultItemDocument>()
        repeat(records.size) { index ->
            var remainder: HostedQueryOutcome? = null
            val page =
                encodeHostedQueryResponse(
                    pending,
                    ReadLimits.Default,
                    maximumResults = ResultLimit.parse(1).refined(),
                ) { suffix ->
                    remainder = suffix
                    HostedOutputRetention.Retained(
                        ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                            .refined()
                    )
                }
                    as HostedResponse.Canonical<*, *, *>
            val semantic = page.semantic as OperationOutcome.Qualified
            val result = semantic.evidence.payload as QueryRunResult
            observed += result.items.values
            assertEquals(listOf(observation), result.walkObservations.values)
            assertTrue(
                QueryLimitationDocument.TRAVERSAL_INCOMPLETE in
                    (semantic.qualification as QueryRunQualification).limitations
            )
            assertEquals(index < records.lastIndex, remainder != null)
            pending = remainder ?: pending
        }
        assertEquals(records, observed)
        assertEquals(listOf(10, 12, 14), records.map { it.record.relation.occurrence.range.startInclusive.value })
        assertEquals(
            1,
            records.map { it.record.relation.source.selector to it.record.relation.target.selector }.distinct().size,
        )
    }

    private data class WalkFixture(
        val records: List<QueryResultItemDocument.TraversalRecord>,
        val observation: QueryWalkObservationDocument,
        val outcome: HostedQueryOutcome,
    )

    private suspend fun walkFixture(): WalkFixture {
        val fixture = RelationPagingFixture.live()
        val read = fixture.firstPage() as RelationReadResult.Qualified
        val records =
            read.batch.facts.map { fact ->
                val relation = checkNotNull(fact.protocolDocument(fixture.references))
                QueryResultItemDocument.TraversalRecord(
                    QueryReferenceDocument.ExactSymbol(relation.target.selector),
                    TraversalRecordDocument(TraversalDepthDocument.parse(1).refined(), relation),
                )
            }
        val observation = walkObservation(fixture.exact, records.size)
        val outcome: HostedQueryOutcome =
            OperationOutcome.Qualified(
                EvidenceEnvelope(
                    CanonicalOperationWireBindings.queryRun.operation.id,
                    fixture.authority.evidenceBasis(),
                    QueryRunResult(
                        bounded<QueryResultItemDocument>(records),
                        bounded(emptyList()),
                        walkObservations = bounded(listOf(observation)),
                    ),
                ),
                QueryRunQualification.create(
                        QueryKnownMinimum.parse(records.size).refined(),
                        listOf(QueryLimitationDocument.TRAVERSAL_INCOMPLETE),
                        QueryQualifiedProgressDocument.TerminalIncomplete(
                            QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                        ),
                    )
                    .refined(),
            )
        return WalkFixture(records, observation, outcome)
    }

    private fun walkObservation(exact: ProtocolText, edgeCount: Int): QueryWalkObservationDocument {
        return QueryWalkObservationDocument(
            QueryReferenceDocument.ExactSymbol(exact),
            RelationKindDocument.REFERENCES,
            ProtocolCount.parse(2).refined(),
            QueryExpandedFrontierDocument.parse(1).refined(),
            TraversalProgressDocument(1, 1, edgeCount.toLong(), 1),
            TraversalStrategyDocument.BreadthFirst,
            bounded(
                listOf(
                    TraversalPartialExpansionDocument.create(
                            exact,
                            TraversalDepthDocument.parse(0).refined(),
                            listOf(RelationLimitationDocument.WORK_LIMIT_REACHED),
                            TraversalExpansionRemainderDocument.NOT_EXPLORED,
                        )
                        .refined()
                )
            ),
            QueryWalkCoverageDocument.terminalIncomplete(
                    listOf(
                        TraversalLimitationDocument.DEPTH_LIMIT_REACHED,
                        TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
                    ),
                    listOf(RelationLimitationDocument.WORK_LIMIT_REACHED),
                )
                .refined(),
        )
    }

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
