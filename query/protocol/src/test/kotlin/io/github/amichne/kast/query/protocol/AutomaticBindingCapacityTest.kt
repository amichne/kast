package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryJoinModeDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBindingName
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryJoinMode
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Stops before any retained row still preserve the observed family or the exact finite failure. */
internal class AutomaticBindingCapacityTest : AutomaticSymbolQueryCase() {
    @Test
    fun `first binding page capacity stop preserves its joined row family`() = runTest {
        val mode =
            QueryJoinMode.Inner.create(
                    QueryBindingName.parse("origin").refined(),
                    QueryBindingName.parse("target").refined(),
                )
                .refined()
        val script =
            Script(listOf(List(20) { row })) { rows, _ ->
                QueryResult(
                    QueryRows.Bindings.of(rows.map { QueryBindingRow.join(mode, it, it).refined() }, mode),
                    emptyList(),
                )
            }
        val state = QueryStateStore()
        val input = bindingRequest(state)
        val recording = QueryInvocationExecution(script.operations)
        val page =
            recording.page(budget) {
                CanonicalQueryProtocol(recording, fixture.references, state).execute(input, fixture.authority, budget)
            }
        val admitted = SymbolInvocationPage.admit(page, input).required()
        val snapshot = QueryRetainedResult.capture(fixture.authority, admitted.execution).required()
        val capacity =
            snapshot.retainedBytes +
                CanonicalQueryCliDocuments.previewBytes(admitted.items) +
                admitted.items.size * QUERY_ROW_REFERENCE_CHARGE_BYTES - 1
        var calls = 0
        val runner =
            AutomaticSymbolQueryRunner(
                state,
                fixture.authority,
                policy(retainedBytes = capacity + input.accountedRequestBytes()),
            ) { _, _ ->
                assertEquals(0, calls++)
                page
            }
        val result = runner.run(input, budget).required()
        assertEquals(QueryInvocationStop.RETAINED_BYTES_LIMIT, result.stop)
        assertEquals(
            QueryRows.Bindings.of(emptyList(), mode),
            (result.execution as QueryExecutionResult.Qualified).result.rows,
        )
        assertEquals(emptyList<QueryResultItemDocument>(), result.items)
        assertEquals(1, calls)
        script.assertDrained()
    }

    @Test
    fun `unobserved binding cancellation retains exact cause without executing a page`() = runTest {
        val state = QueryStateStore()
        val script = Script(emptyList())
        val input = bindingRequest(state)
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references, state)
                .executeAutomatically(input, fixture.authority, budget, policy(cancelled = { true }))
        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INVOCATION_CANCELLED)
            ),
            result,
        )
        script.assertDrained()
    }

    private fun bindingRequest(state: QueryStateStore): QueryRunRequest.Run {
        val complete =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Symbols.of(listOf(row)), emptyList()),
                QueryCoverage.Complete(QueryCount.parse(1).refined()),
            )
        val issued =
            state.issueResult(request, QueryRetainedResult.capture(fixture.authority, complete).refined())
                as QueryResultIssuance.Issued
        return request.copy(
            output = QueryOutputDocument.BindingRows,
            steps =
                bounded(
                    listOf(
                        QueryStepDocument.Join(
                            QueryJoinModeDocument.Inner(
                                QueryBindingNameDocument.parse("origin").refined(),
                                QueryBindingNameDocument.parse("target").refined(),
                            ),
                            QueryFromDocument.Result(issued.reference),
                        )
                    )
                ),
        )
    }
}
