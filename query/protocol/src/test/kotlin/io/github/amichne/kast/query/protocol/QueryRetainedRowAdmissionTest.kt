package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBindingName
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.symbol.contract.SymbolDescription
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryRetainedRowAdmissionTest {
    private val fixture = RelationPagingFixture.published()
    private val other = RelationPagingFixture(fixture.authority, "other")
    private val store = QueryStateStore()
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(10000).refined(),
        )
    private val output = QueryOutputDocument.Symbols(bounded(emptyList()))
    private val execution =
        QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE)

    @Test
    fun `retained binding pairs page with row IDs and reject symbol presentation`() = runTest {
        val issued = retainedBindingPairs()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Presentation must not execute a query") },
                CanonicalQueryReferences(),
                store,
            )
        val page =
            protocol.execute(QueryRunRequest.ReadResult.bindingRows(issued.reference), fixture.authority, budget)
                as OperationOutcome.Complete
        val pairs = page.evidence.payload.items.values.map { it as QueryResultItemDocument.BindingRow }
        assertEquals(2, pairs.size)
        assertEquals(issued.rowIds, pairs.map { it.rowId })
        assertEquals(listOf("left", "right"), listOf(pairs.first().left.name.value, pairs.first().right.name.value))
        val wrong =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(issued.reference, output = output),
                fixture.authority,
                budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE),
            wrong.reason,
        )
    }

    @Test
    fun `selected retained binding row admits named projection before evaluation`() = runTest {
        val issued = retainedBindingPairs()
        var selected: QueryRetainedResult.Bindings? = null
        val protocol = CanonicalQueryProtocol(
            QueryOperations { request ->
                selected = (request.plan as AdmittedQueryPlan.Retained).source as QueryRetainedResult.Bindings
                emptyExecution()
            },
            CanonicalQueryReferences(),
            store,
        )
        val source = QueryFromDocument.Result(issued.reference, bounded(listOf(issued.rowIds[1])))
        val request = run(source).copy(
            steps = bounded(listOf(QueryStepDocument.ProjectBinding(QueryBindingNameDocument.parse("right").refined())))
        )
        assertInstanceOf(OperationOutcome.Complete::class.java, protocol.execute(request, fixture.authority, budget))
        assertEquals(1, requireNotNull(selected).bindingRows.size)
        assertEquals(
            listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE),
            (requireNotNull(selected).coverage as QueryCoverage.Qualified).limitations,
        )
    }

    @Test
    fun `issued output row identity seeds a later query without reconstructing the symbol`() = runTest {
        var selected: QueryRetainedResult.Symbols? = null
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { request ->
                    when (val plan = request.plan) {
                        is AdmittedQueryPlan.Symbols -> completeRows()
                        is AdmittedQueryPlan.Retained -> {
                            selected = plan.source as QueryRetainedResult.Symbols
                            emptyExecution()
                        }
                        is AdmittedQueryPlan.ExactReferences -> error("Unexpected exact source")
                    }
                },
                CanonicalQueryReferences(),
                store,
            )
        val first =
            protocol.execute(
                run(QueryFromDocument.Symbols(discovery())).copy(retention = QueryRetentionModeDocument.RETAIN),
                fixture.authority,
                budget,
            ) as OperationOutcome.Complete
        val retained = first.evidence.payload.retention as QueryResultRetention.Retained
        val item = first.evidence.payload.items.values[1] as QueryResultItemDocument.ExactSymbol
        val source = QueryFromDocument.Result(retained.reference, bounded(listOf(requireNotNull(item.rowId))))
        protocol.execute(run(source), fixture.authority, budget)
        assertEquals(listOf(other.selector), requireNotNull(selected).symbols.map(QuerySymbol::selector))
    }

    @Test
    fun `issued row identity selects only the original proven row and qualifies unchecked omissions`() = runTest {
        val issued = retainedTwoRows()
        var selected: QueryRetainedResult.Symbols? = null
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { request ->
                    selected = (request.plan as AdmittedQueryPlan.Retained).source as QueryRetainedResult.Symbols
                    emptyExecution()
                },
                CanonicalQueryReferences(),
                store,
            )
        val request = run(QueryFromDocument.Result(issued.reference, bounded(listOf(issued.rowIds[1]))))
        assertInstanceOf(OperationOutcome.Complete::class.java, protocol.execute(request, fixture.authority, budget))
        val admitted = requireNotNull(selected)
        assertEquals(listOf(other.selector), admitted.symbols.map(QuerySymbol::selector))
        assertEquals(
            listOf(QueryLimitation.ROW_SELECTION_INCOMPLETE),
            (admitted.coverage as QueryCoverage.Qualified).limitations,
        )
    }

    @Test
    fun `unknown row identity rejects before semantic execution`() = runTest {
        val issued = retainedTwoRows()
        val unknown = QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000001").refined()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("An unknown row must not execute") },
                CanonicalQueryReferences(),
                store,
            )
        val request = run(QueryFromDocument.Result(issued.reference, bounded(listOf(unknown))))
        val rejection = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_ROW_UNAVAILABLE),
            rejection.reason,
        )
    }

    @Test
    fun `issued row identity is scoped to its owning retained result`() = runTest {
        val first = retainedTwoRows()
        val second = retainedTwoRows()
        assertNotEquals(first.reference, second.reference)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("A foreign result row must not execute") },
                CanonicalQueryReferences(),
                store,
            )
        val source = QueryFromDocument.Result(first.reference, bounded(listOf(second.rowIds.first())))
        val rejection = protocol.execute(run(source), fixture.authority, budget) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_ROW_UNAVAILABLE),
            rejection.reason,
        )
    }

    @Test
    fun `issued row identities are immutable and stable across restoration and presentation`() = runTest {
        val issued = retainedTwoRows()
        val restored = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        assertEquals(issued.rowIds, restored.rowIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (restored.rowIds as MutableList<QueryResultRowReference>).clear()
        }
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Presenting retained rows must not execute a query") },
                CanonicalQueryReferences(),
                store,
            )
        val request = QueryRunRequest.ReadResult.symbols(issued.reference, output = output)
        val first = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Complete
        val second = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Complete
        val firstIds = first.evidence.payload.items.values.map { (it as QueryResultItemDocument.ExactSymbol).rowId }
        val secondIds = second.evidence.payload.items.values.map { (it as QueryResultItemDocument.ExactSymbol).rowId }
        assertEquals(issued.rowIds, firstIds)
        assertEquals(firstIds, secondIds)
        val after = store.restoreResult(issued.reference, fixture.authority) as QueryResultRestoration.Restored
        assertEquals(issued.rowIds, after.rowIds)
        assertEquals(2, (after.result as QueryRetainedResult.Symbols).symbols.size)
    }

    @Test
    fun `partial retained right input cannot establish difference`() = runTest {
        val issued = retainedTwoRows()
        val source = QueryFromDocument.Result(issued.reference)
        val right = QueryFromDocument.Result(issued.reference, bounded(listOf(issued.rowIds.first())))
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Incomplete difference must not execute") },
                CanonicalQueryReferences(),
                store,
            )
        val request = run(source).copy(steps = bounded(listOf(QueryStepDocument.Difference(right))))
        val rejection = protocol.execute(request, fixture.authority, budget) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RIGHT_INPUT_INCOMPLETE),
            rejection.reason,
        )
    }

    private fun retainedBindingPairs(): QueryResultIssuance.Issued {
        val symbol = QuerySymbol(SymbolDescription.from(fixture.selector), emptyList())
        val mode =
            QueryJoinMode.Inner.create(
                    QueryBindingName.parse("left").refined(),
                    QueryBindingName.parse("right").refined(),
                )
                .refined()
        val pair = QueryBindingRow.join(mode, symbol, symbol).refined()
        val result =
            QueryExecutionResult.Complete(
                QueryResult(QueryRows.Bindings.of(listOf(pair, pair), mode), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(2).refined()),
            )
        val retained = QueryRetainedResult.capture(fixture.authority, result).refined()
        val request = run(QueryFromDocument.Symbols(discovery())).copy(output = QueryOutputDocument.BindingRows)
        val issued = store.issueResult(request, retained) as QueryResultIssuance.Issued
        return issued
    }

    private fun retainedTwoRows(): QueryResultIssuance.Issued {
        val retained = QueryRetainedResult.capture(fixture.authority, completeRows()).refined()
        return store.issueResult(run(QueryFromDocument.Symbols(discovery())), retained) as QueryResultIssuance.Issued
    }

    private fun completeRows(): QueryExecutionResult.Complete {
        val rows = listOf(fixture.selector, other.selector).map { QuerySymbol(SymbolDescription.from(it), emptyList()) }
        return QueryExecutionResult.Complete(
            QueryResult(QueryRows.Symbols.of(rows), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(rows.size).refined()),
        )
    }

    private fun run(from: QueryFromDocument) = QueryRunRequest.Run(from, bounded(emptyList()), output, execution)

    private fun discovery() =
        QueryDiscoveryDocument(
            QueryMatchDocument.All,
            QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refined())), null, null),
            bounded(listOf(QueryDeclarationKindDocument.CLASS)),
        )

    private fun emptyExecution() =
        QueryExecutionResult.Complete(
            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
            QueryCoverage.Complete(QueryCount.parse(0).refined()),
        )

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
