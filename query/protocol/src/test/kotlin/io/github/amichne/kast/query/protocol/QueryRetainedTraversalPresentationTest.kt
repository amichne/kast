package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QueryRetainedTraversalPresentationTest {
    private val fixture = RelationPagingFixture.published()
    private val store = QueryStateStore(clock = { 0L })
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(100).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )

    @Test
    fun `retained traversal records retain original depths facts and IDs across presentation pages`() = runTest {
        val issued = issueTraversalRecords()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Presentation must not execute traversal") },
                fixture.references,
                store,
            )
        val first =
            protocol.executePage(
                QueryRunRequest.ReadResult.traversalRecords(issued.reference),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        val last =
            protocol.executePage(
                QueryRunRequest.ReadResult.traversalRecords(
                    issued.reference,
                    requireNotNull(first.evidence.payload.nextCursor),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        val items =
            (first.evidence.payload.items.values + last.evidence.payload.items.values).map {
                it as QueryResultItemDocument.TraversalRecord
            }
        assertEquals(issued.rowIds, items.map { it.rowId })
        assertEquals((0 until 101).map { 1 + it % 3 }, items.map { it.record.depth.value })
        assertEquals(
            (0 until 101).map { 8 + it * 2 },
            items.map { it.record.relation.occurrence.range.startInclusive.value },
        )
        assertEquals(101, items.map { it.rowId }.toSet().size)
        assertNull(last.evidence.payload.nextCursor)
    }

    private fun issueTraversalRecords(): QueryResultIssuance.Issued {
        val records = (0 until 101).map(::traversalRecord)
        val rows = records.map {
            QuerySymbol(
                SymbolDescription.from(fixture.selector),
                listOf(it.fact),
                walkArrival = QueryWalkArrival.Proven.one(it),
            )
        }
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Symbols.of(rows), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(101).refined()),
            )
        return store.issueResult(
            run(QueryOutputDocument.TraversalRecords),
            QueryRetainedResult.capture(fixture.authority, execution).refined(),
        ) as QueryResultIssuance.Issued
    }

    @Test
    fun `retained rows without traversal proof reject depth bearing presentation`() = runTest {
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Presentation must not execute traversal") },
                fixture.references,
                store,
            )
        val unproven =
            QueryExecutionResult.Complete.create(
                QueryResult(
                    QueryRows.Symbols.of(listOf(QuerySymbol(SymbolDescription.from(fixture.selector), emptyList()))),
                    emptyList(),
                ),
                QueryCoverage.Complete(QueryCount.parse(1).refined()),
            )
        val unavailable =
            store.issueResult(
                run(QueryOutputDocument.Symbols(bounded(emptyList()))),
                QueryRetainedResult.capture(fixture.authority, unproven).refined(),
            ) as QueryResultIssuance.Issued
        val rejected =
            protocol.executePage(
                QueryRunRequest.ReadResult.traversalRecords(unavailable.reference),
                fixture.authority,
                budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE),
            rejected.reason,
        )
    }

    @Test
    fun `retained complete empty walk projects its actual effective domain and exact seed`() = runTest {
        // Exhausted native traversal is the starting evidence; this proves retained public projection.
        val observation = emptyWalkObservation()
        val original = run(QueryOutputDocument.Symbols(bounded(emptyList())))
        val issued = issueEmptyWalk(observation, original)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Retained empty walk must not execute semantic work") },
                fixture.references,
                store,
            )
        val first =
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        val replay =
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(
                    issued.reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(0, first.evidence.payload.items.values.size)
        val projected = first.evidence.payload.walkObservations.values.single()
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument.SOURCE_DOMAIN,
            projected.requestedDomain,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument.Workspace,
            projected.effectiveDomain.scope,
        )
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument.TEST_ONLY,
            projected.effectiveDomain.sourcePolicy,
        )
        assertEquals(observation.question.domainFingerprint.value, projected.domainFingerprint.value)
        assertEquals(io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument.Complete, projected.coverage)
        assertEquals(first.evidence.payload.walkObservations, replay.evidence.payload.walkObservations)
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryQuestionDocument.from(original),
            first.evidence.payload.question,
        )
    }

    private fun issueEmptyWalk(
        observation: io.github.amichne.kast.query.contract.QueryWalkObservation,
        original: QueryRunRequest.Run,
    ): QueryResultIssuance.Issued {
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(
                    QueryRows.Symbols.of(emptyList()),
                    emptyList(),
                    walkObservations = listOf(observation),
                ),
                QueryCoverage.Complete(QueryCount.parse(0).refined()),
            )
        return store.issueResult(original, QueryRetainedResult.capture(fixture.authority, execution).refined())
            as QueryResultIssuance.Issued
    }

    private fun emptyWalkObservation(): io.github.amichne.kast.query.contract.QueryWalkObservation {
        val domain =
            io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace(
                io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy.TEST_ONLY,
                io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy.EXCLUDE,
                io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.EXCLUDE,
            )
        val expansion = io.github.amichne.kast.relation.contract.RelationSearchBoundary.Explicit(domain)
        val traversalBudget =
            TraversalBudget(
                ResultLimit.parse(100).refined(),
                TraversalByteLimit.parse(100_000).refined(),
                fixture.budget.resources.workUnitLimit,
                fixture.budget.resources.elapsedTimeLimit,
                TraversalDepthLimit.parse(2).refined(),
                TraversalFrontierLimit.parse(100).refined(),
                fixture.budget,
            )
        val plan =
            TraversalPlan.start(fixture.selector, RelationMeaning.References, traversalBudget, expansion = expansion)
                .refined()
        val page =
            io.github.amichne.kast.traversal.contract.TraversalPage.fromBoundary(
                    plan,
                    emptyList(),
                    0L,
                    1L,
                    0L,
                    1,
                    io.github.amichne.kast.traversal.contract.TraversalProgress.restore(1L, 1L, 0L, 0).refined(),
                )
                .refined()
        val complete =
            io.github.amichne.kast.traversal.contract.TraversalResult.complete(page)
                as io.github.amichne.kast.traversal.contract.TraversalResult.Complete
        return io.github.amichne.kast.query.contract.QueryWalkObservation.from(complete)
    }

    private fun traversalRecord(index: Int): TraversalRecord {
        val oneHop = fixture.budget
        val traversalBudget =
            TraversalBudget(
                ResultLimit.parse(100).refined(),
                TraversalByteLimit.parse(100_000).refined(),
                oneHop.resources.workUnitLimit,
                oneHop.resources.elapsedTimeLimit,
                TraversalDepthLimit.parse(3).refined(),
                TraversalFrontierLimit.parse(100).refined(),
                oneHop,
            )
        val plan = TraversalPlan.start(fixture.selector, RelationMeaning.References, traversalBudget).refined()
        val request = RelationRequest.start(fixture.selector, RelationMeaning.References, oneHop)
        val related =
            RelationEndpoint.resolve(
                    fixture.authority,
                    fixture.selector.scope,
                    CompilerGroundedSymbolEvidence.fromSelector(fixture.selector),
                )
                .refined()
        val fact =
            RelationFact.create(
                    request,
                    related,
                    request.subject,
                    RelationOccurrence.fromBoundary(request.subject.file, 8 + index * 2, 9 + index * 2).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        return TraversalRecord.create(
                plan,
                request.subject.fingerprint,
                TraversalDepth.parse(1 + index % 3).refined(),
                fact,
            )
            .refined()
    }

    private fun run(output: QueryOutputDocument = QueryOutputDocument.Occurrences) =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                )
            ),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
