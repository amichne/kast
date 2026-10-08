package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSamplesDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.QueryPublishedPage
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
    fun `zero row oversized evidence drains immutable observations within the byte allowance`() = runTest {
        val fixture = walkFixture()
        val original = fixture.outcome as OperationOutcome.Qualified
        val observations =
            List(6) { index ->
                fixture.observation.copy(
                    domainFingerprint =
                        QueryRelationDomainFingerprint.parse((index + 1).toString().repeat(64)).refined()
                )
            }
        var pending: HostedQueryOutcome = emptyEvidencePage(original, observations)
        val seen = mutableListOf<QueryWalkObservationDocument>()
        var requests = 0
        while (true) {
            requests++
            assertTrue(requests <= 6)
            var suffix: HostedQueryOutcome? = null
            val response =
                encodeHostedQueryResponse(
                    pending,
                    maximumResults = ResultLimit.parse(1).refined(),
                    maximumBytes = ReturnedByteLimit.parse(6500).refined(),
                ) { remaining ->
                    suffix = remaining
                    HostedOutputRetention.Retained(
                        ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                            .refined()
                    )
                }
            assertTrue(response is HostedResponse.Canonical<*, *, *>)
            val page = response as HostedResponse.Canonical<*, *, *>
            assertTrue(page.document.toByteArray().size <= 6500)
            val result = (page.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult
            assertTrue(result.items.values.isEmpty())
            assertTrue(result.walkObservations.values.isNotEmpty())
            seen += result.walkObservations.values
            pending = suffix ?: break
        }
        assertTrue(requests > 1)
        assertEquals(observations, seen)
    }

    private fun emptyEvidencePage(
        original: OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>,
        observations: List<QueryWalkObservationDocument>,
    ): HostedQueryOutcome =
        original.copy(
            evidence =
                original.evidence.copy(
                    payload =
                        original.evidence.payload.copy(
                            items = bounded(emptyList()),
                            walkObservations = bounded(observations),
                        )
                )
        )

    @Test
    fun `query walk pages retain occurrence records and depth frontier progress and incomplete coverage`() = runTest {
        val (records, observation, outcome) = walkFixture()
        var pending = outcome
        val observed = mutableListOf<QueryResultItemDocument>()
        val evidence = mutableListOf<QueryWalkObservationDocument>()
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
            evidence += result.walkObservations.values
            assertTrue(
                QueryLimitationDocument.TRAVERSAL_INCOMPLETE in
                    (semantic.qualification as QueryRunQualification).limitations
            )
            assertEquals(index < records.lastIndex, remainder != null)
            pending = remainder ?: pending
        }
        assertEquals(records, observed)
        assertEquals(listOf(observation), evidence)
        assertEquals(listOf(10, 12, 14), records.map { it.record.relation.occurrence.range.startInclusive.value })
        assertEquals(
            1,
            records.map { it.record.relation.source.selector to it.record.relation.target.selector }.distinct().size,
        )
    }

    @Test
    fun `retention capacity preserves a useful prefix and the original incomplete coverage`() = runTest {
        val (records, _, outcome) = walkFixture()
        var published: QueryPublishedPage? = null
        val response =
            encodeHostedQueryResponse(
                outcome,
                maximumResults = ResultLimit.parse(1).refined(),
                published = { published = it.page },
                retain = { HostedOutputRetention.CapacityExceeded },
            )
                as HostedResponse.Canonical<*, *, *>
        val qualified = response.semantic as OperationOutcome.Qualified
        val result = qualified.evidence.payload as QueryRunResult
        assertEquals(listOf(records.first()), result.items.values)
        assertTrue(result.walkObservations.values.isEmpty())
        val progress =
            (qualified.qualification as QueryRunQualification).progress
                as QueryQualifiedProgressDocument.RetentionUnavailable
        assertEquals(
            QueryPreparedCoverageDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
            progress.upstream,
        )
        assertEquals(
            (outcome as OperationOutcome.Qualified).qualification.knownMinimum,
            (qualified.qualification as QueryRunQualification).knownMinimum,
        )
        assertEquals(response.semantic, published)
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
                        fixtureQueryQuestion(),
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
            io.github.amichne.kast.protocol.contract.TraversalExtentDocument.ThroughDepth(
                ProtocolCount.parse(2).refined()
            ),
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
                            knownMinimum = QueryKnownMinimum.parse(0).refined(),
                            omissions = unmeasuredPageOmissions(),
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
            requestedDomain = QueryRelationRequestedDomainDocument.WORKSPACE,
            effectiveDomain = fixtureWalkDomain(),
            domainFingerprint = QueryRelationDomainFingerprint.parse("1".repeat(64)).refined(),
        )
    }

    private fun unmeasuredPageOmissions(): BoundedProtocolList<RelationOmissionDocument> =
        BoundedProtocolList.create(
                listOf(
                    RelationOmissionDocument.create(
                            RelationProviderDocument.INTELLIJ_REFERENCES_V2,
                            RelationLimitationDocument.WORK_LIMIT_REACHED,
                            RelationOmissionMeasurementDocument.UnmeasuredOnPage,
                            RelationOmissionSamplesDocument.complete(
                                    BoundedProtocolList.create(emptyList<RelationOmissionLocationDocument>()).refined()
                                )
                                .refined(),
                        )
                        .refined()
                )
            )
            .refined()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}

private fun fixtureQueryQuestion(): QueryQuestionDocument {
    fun <Value, Failure> fixtureValue(value: Refinement<Value, Failure>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid question fixture: ${value.failure}")
        }
    return QueryQuestionDocument(
        QueryFromDocument.Location(
            fixtureValue(ProtocolText.parse("Fixture.kt")),
            fixtureValue(ProtocolOffset.parse(0)),
        ),
        fixtureValue(BoundedProtocolList.create(emptyList())),
        QueryOutputDocument.Symbols(fixtureValue(BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.NAME)))),
    )
}

private fun fixtureWalkDomain(): QueryRelationDomainDocument =
    QueryRelationDomainDocument(
        QuerySemanticScopeDocument.Workspace,
        QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
        QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        QueryDiscoverySourceSetsDocument.All,
        null,
        null,
        (BoundedProtocolList.create(emptyList<QueryDeclarationKindDocument>()) as Refinement.Refined).value,
    )
