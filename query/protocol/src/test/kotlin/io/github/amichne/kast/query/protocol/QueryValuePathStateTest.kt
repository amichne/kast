package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactFlowSemantics
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryValuePathStateTest {
    private val fixture = RelationPagingFixture.published()

    @Test
    fun `single store restores exact path rows and stable row identities without semantic execution`() {
        val paths = listOf(path(1), path(3))
        val retained = retained(paths)
        val store = QueryStateStore()
        val issued = assertInstanceOf(QueryResultIssuance.Issued::class.java, store.issueResult(request(), retained))
        val first =
            assertInstanceOf(
                QueryResultRestoration.Restored::class.java,
                store.restoreResult(issued.reference, fixture.authority),
            )
        val second =
            assertInstanceOf(
                QueryResultRestoration.Restored::class.java,
                store.restoreResult(issued.reference, fixture.authority),
            )
        assertSame(retained, first.result)
        assertEquals(issued.rowIds, first.rowIds)
        assertEquals(first.rowIds, second.rowIds)
        assertNotEquals(first.rowIds[0], first.rowIds[1])
        assertThrows(UnsupportedOperationException::class.java) {
            (first.rowIds as MutableList<QueryResultRowReference>).clear()
        }
        val restored = assertInstanceOf(QueryRetainedResult.ValuePaths::class.java, first.result)
        assertSame(paths[1], restored.selectRows(listOf(1)).refined().valuePaths.single())
    }

    @Test
    fun `value path presentation slices retained rows with the original identity and qualification`() {
        val paths = listOf(path(1), path(3))
        val store = QueryStateStore()
        val issued =
            assertInstanceOf(
                QueryResultIssuance.Issued::class.java,
                store.issueResult(request(), retained(paths)),
            )
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        val presentation =
            RetainedQueryPresentation.create(
                    restored,
                    QueryRunRequest.ReadResult.valuePaths(issued.reference, QueryResultCursor.parse(1).refined()),
                    (io.github.amichne.kast.kernel.ResultLimit.parse(100) as Refinement.Refined).value,
                )
                .refined()
        assertSame(paths[1], (presentation.result.rows as QueryRows.ValuePaths).values.single())
        assertEquals(listOf(issued.rowIds[1]), presentation.rowIds)
        assertEquals(
            setOf(QueryLimitation.RELATION_INCOMPLETE, QueryLimitation.ROW_SELECTION_INCOMPLETE),
            presentation.coverage?.limitations?.toSet(),
        )
        assertNull(retainedRows(restored.result, QueryOutputDocument.Occurrences, 0, 1))
    }

    @Test
    fun `value path storage obeys quota and rejects stale basis without changing original retained rows`() {
        val retained = retained(listOf(path(1)))
        assertEquals(
            QueryResultIssuance.CapacityExceeded,
            QueryStateStore(maximumBytes = retained.retainedBytes - 1).issueResult(request(), retained),
        )
        val store = QueryStateStore()
        val issued = store.issueResult(request(), retained) as QueryResultIssuance.Issued
        val foreign = SemanticReadLease(fixture.authority.workspaceRoot, EvidenceGeneration.parse(8).refined())
        assertEquals(QueryResultRestoration.StaleBasis, store.restoreResult(issued.reference, foreign))
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        assertSame(retained, restored.result)
        assertEquals(issued.rowIds, restored.rowIds)
    }

    @Test
    fun `last presentation slice cannot claim complete investigation from a conserved original ledger`() {
        val paths = listOf(path(1), path(3))
        val ledger = conservedLedger(paths)
        val complete =
            QueryExecutionResult.Complete.create(
                QueryResult(
                    QueryRows.ValuePaths.fromInvestigation(ledger).refined(),
                    emptyList(),
                ),
                QueryCoverage.Complete(QueryCount.parse(2).refined()),
            )
        val retained = QueryRetainedResult.capture(fixture.authority, complete).refined()
        val store = QueryStateStore()
        val issued = store.issueResult(request(), retained) as QueryResultIssuance.Issued
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        val presentation =
            RetainedQueryPresentation.create(
                    restored,
                    QueryRunRequest.ReadResult.valuePaths(issued.reference, QueryResultCursor.parse(1).refined()),
                    (io.github.amichne.kast.kernel.ResultLimit.parse(100) as Refinement.Refined).value,
                )
                .refined()
        assertSame(
            ledger,
            ((presentation.result.rows as QueryRows.ValuePaths).accounting as QueryValuePathAccounting.Investigated)
                .ledger,
        )
        assertEquals(listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE), presentation.coverage?.limitations)
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(
                presentation.result,
                QueryCoverage.Complete(QueryCount.parse(1).refined()),
            ),
        )
    }

    private fun conservedLedger(paths: List<QueryImpactPath>): QueryImpactLedger {
        return QueryImpactLedger.fromEvidence(
                paths.map { it.producer },
                RelationSearchBoundary.RETAINED_SUBJECT,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                paths.map { (it.terminal as QueryImpactTerminal.SupportedDomainEnd).observation },
                paths,
                originalProducers =
                    paths.map { path ->
                        QueryImpactProducer.admit(
                                path.producer,
                                ValueInvocation.fromCompiler(
                                        path.producer.enclosing,
                                        path.producer.range,
                                        path.producer.enclosing,
                                    )
                                    .refined(),
                            )
                            .refined()
                    },
            )
            .refined()
    }

    private fun path(offset: Int): QueryImpactPath {
        val domain = RelationRequest.start(fixture.selector, RelationMeaning.References, fixture.budget)
        val source =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(fixture.selector),
                    ExactDeclarationTextRange.parse(offset, offset + 1).refined(),
                    ValueRole.ExpressionResult,
                )
                .refined()
        val terminal =
            QueryImpactTerminal.SupportedDomainEnd.admit(
                    ValueFlowStep.fromCompiler(
                            source,
                            emptyList(),
                            emptyList(),
                            ValueFlowTerminal.SupportedDomainExhausted,
                            domain,
                            RelationWorkCount.parse(1).refined(),
                        )
                        .refined()
                )
                .refined()
        return QueryImpactPath.fromEvidence(source, emptyList(), QueryImpactRepresentation.NotModeled, terminal)
            .refined()
    }

    private fun retained(paths: List<QueryImpactPath>) =
        QueryRetainedResult.capture(
                fixture.authority,
                QueryExecutionResult.Qualified(
                    QueryResult(QueryRows.ValuePaths.of(paths), emptyList()),
                    QueryCoverage.Qualified.create(
                            QueryCount.parse(paths.size).refined(),
                            setOf(QueryLimitation.RELATION_INCOMPLETE),
                        )
                        .refined(),
                ),
            )
            .refined()

    private fun request() =
        QueryRunRequest.Run(
            QueryFromDocument.References(
                BoundedProtocolList.create(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact))).refined()
            ),
            BoundedProtocolList.create(emptyList<QueryStepDocument>()).refined(),
            QueryOutputDocument.ValuePaths,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            completion = io.github.amichne.kast.protocol.contract.QueryCompletionPolicyDocument.Progressive,
        )
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
