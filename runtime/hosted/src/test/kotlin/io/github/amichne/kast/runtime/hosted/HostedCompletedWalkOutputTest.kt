package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryScopeMembershipAuthorityDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.QueryWalkScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
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

class HostedCompletedWalkOutputTest {
    @Test
    fun `completed walk with two hundred independent exclusions drains at 65536 bytes`() = runTest {
        val fixture = completedWalkFixture()
        val original = fixture.outcome
        val completed = fixture.observation
        val exclusions = List(200) { scopeExclusion(completed, it) }
        val observations = exclusions.map { completed.copy(scopeExclusions = bounded(listOf(it))) }
        var pending: HostedQueryOutcome =
            OperationOutcome.Complete(
                original.evidence.copy(
                    payload = original.evidence.payload.copy(walkObservations = bounded(observations))
                )
            )
        val seen = mutableListOf<QueryWalkScopeExclusionDocument>()
        val rows = mutableListOf<QueryResultItemDocument>()
        var requests = 0
        while (true) {
            assertTrue(++requests <= 20, "Output must advance to a finite final page")
            var suffix: HostedQueryOutcome? = null
            val response =
                encodeHostedQueryResponse(
                    pending,
                    maximumBytes = ReturnedByteLimit.parse(65_536).refined(),
                ) { remaining ->
                    suffix = remaining
                    HostedOutputRetention.Retained(
                        ProtocolText.parse(HostedQueryContinuations.prefix + "00000000-0000-0000-0000-000000000000")
                            .refined()
                    )
                }
            assertTrue(response is HostedResponse.Canonical<*, *, *>)
            val page = response as HostedResponse.Canonical<*, *, *>
            assertTrue(page.document.toByteArray(Charsets.UTF_8).size <= 65_536)
            val result = completedPage(page)
            rows += result.items.values
            assertTrue(result.walkObservations.values.isNotEmpty(), "Evidence-only pages must publish evidence")
            result.walkObservations.values.forEach { observed ->
                assertEquals(completed, observed.copy(scopeExclusions = bounded(emptyList())))
                seen += observed.scopeExclusions.values
            }
            pending = suffix ?: break
        }
        assertTrue(requests > 1)
        assertEquals(fixture.records, rows)
        assertEquals(exclusions, seen)
        println("completed_walk_output pages=$requests exclusions=${seen.size} rows=${rows.size} maximumBytes=65536")
    }

    @Test
    fun `single indivisible oversized walk observation rejects without issuing another continuation`() = runTest {
        val fixture = completedWalkFixture()
        val original = fixture.outcome
        val completed = fixture.observation
        val oversized = completed.copy(scopeExclusions = bounded(List(200) { scopeExclusion(completed, it) }))
        val semantic: HostedQueryOutcome =
            OperationOutcome.Complete(
                original.evidence.copy(
                    payload =
                        original.evidence.payload.copy(
                            items = bounded(emptyList()),
                            walkObservations = bounded(listOf(oversized)),
                        )
                )
            )
        var retentions = 0
        val response =
            encodeHostedQueryResponse(semantic, maximumBytes = ReturnedByteLimit.parse(65_536).refined()) {
                retentions++
                error("No advancing prefix can fit this indivisible observation")
            }
        assertTrue(response is HostedResponse.Oversized)
        assertEquals(semantic, (response as HostedResponse.Oversized).semantic)
        assertEquals(0, retentions)
        assertTrue(response.document.toByteArray(Charsets.UTF_8).size <= 65_536)
    }

    private fun scopeExclusion(observation: QueryWalkObservationDocument, index: Int): QueryWalkScopeExclusionDocument {
        val range =
            SourceRangeDocument.create(
                    ProtocolOffset.parse(index * 10).refined(),
                    ProtocolOffset.parse(index * 10 + 1).refined(),
                )
                .refined()
        val target =
            QueryExcludedCompilerTargetDocument.create(
                    file = ProtocolText.parse("stdlib/Unit.kt").refined(),
                    range = range,
                    name = ProtocolText.parse("Unit").refined(),
                    kind = SymbolKindDocument.CLASSLIKE,
                    compilerEvidence =
                        CompilerSymbolEvidenceDocument.fromSignature(
                                CompilerSignatureDocument.ClassLike(ProtocolText.parse("kotlin.Unit").refined())
                            )
                            .refined(),
                )
                .refined()
        return QueryWalkScopeExclusionDocument(
            observation.subject,
            TraversalDepthDocument.parse(1).refined(),
            QueryScopeExclusionDocument(
                occurrence =
                    RelationOccurrenceDocument(
                        ProtocolText.parse("candidate:$index").refined(),
                        ProtocolText.parse("Seed.kt").refined(),
                        range,
                    ),
                target = target,
                reason = QueryScopeExclusionReasonDocument.LIBRARY_POLICY,
                membershipAuthority = QueryScopeMembershipAuthorityDocument.IMPORTED_MODEL_NATIVE_SCOPE,
                requestedDomain = observation.requestedDomain,
                effectiveDomain = observation.effectiveDomain,
                domainFingerprint = observation.domainFingerprint,
            ),
        )
    }

    private fun completedPage(page: HostedResponse.Canonical<*, *, *>): QueryRunResult =
        when (val semantic = page.semantic) {
            is OperationOutcome.Complete -> semantic.evidence.payload as QueryRunResult
            is OperationOutcome.Qualified -> {
                val qualification = semantic.qualification as QueryRunQualification
                assertEquals(listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED), qualification.limitations)
                assertTrue(qualification.progress is QueryQualifiedProgressDocument.Resumable)
                semantic.evidence.payload as QueryRunResult
            }
            is OperationOutcome.Rejected -> error("Completed walk output was rejected")
        }

    private suspend fun completedWalkFixture(): CompletedWalkFixture {
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
        val observation =
            QueryWalkObservationDocument(
                subject = QueryReferenceDocument.ExactSymbol(fixture.exact),
                relation = RelationKindDocument.CALLEES,
                maximumDepth = ProtocolCount.parse(2).refined(),
                expandedFrontier = QueryExpandedFrontierDocument.parse(4).refined(),
                progress =
                    TraversalProgressDocument(
                        checkpointSequence = 4,
                        totalReads = 4,
                        totalEdges = 3,
                        maximumDepthReached = 1,
                    ),
                strategy = TraversalStrategyDocument.BreadthFirst,
                partialExpansions = bounded(emptyList()),
                coverage = QueryWalkCoverageDocument.Complete,
                requestedDomain = QueryRelationRequestedDomainDocument.WORKSPACE,
                effectiveDomain = fixtureWalkDomain(),
                domainFingerprint = QueryRelationDomainFingerprint.parse("1".repeat(64)).refined(),
            )
        val outcome =
            OperationOutcome.Complete(
                EvidenceEnvelope(
                    CanonicalOperationWireBindings.queryRun.operation.id,
                    fixture.authority.evidenceBasis(),
                    QueryRunResult(
                        question = fixtureQueryQuestion(),
                        items = bounded<QueryResultItemDocument>(records),
                        failures = bounded(emptyList()),
                        walkObservations = bounded(listOf(observation)),
                    ),
                )
            )
        return CompletedWalkFixture(records, observation, outcome)
    }

    private data class CompletedWalkFixture(
        val records: List<QueryResultItemDocument.TraversalRecord>,
        val observation: QueryWalkObservationDocument,
        val outcome: OperationOutcome.Complete<QueryRunResult>,
    )

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
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
        scope = QuerySemanticScopeDocument.Workspace,
        sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
        generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        libraries = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        sourceSets = QueryDiscoverySourceSetsDocument.All,
        directory = null,
        packageName = null,
        declarationKinds =
            (BoundedProtocolList.create(emptyList<QueryDeclarationKindDocument>()) as Refinement.Refined).value,
    )
