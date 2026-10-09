package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryReplayTest : AutomaticSymbolQueryCase() {
    private var first: QueryExecutionContinuation? = null
    private var successor: QueryExecutionContinuation? = null

    @Test
    fun `a published page replay has no new receipt and cannot duplicate its rows`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row), listOf(row)))
        val state = QueryStateStore()
        val recording = QueryInvocationExecution(script.operations)
        val pages = CanonicalQueryProtocol(recording, fixture.references, state)
        var calls = 0
        val accumulated =
            AutomaticSymbolQueryRunner(state, fixture.authority, policy()) { action, remaining ->
                    calls++
                    recording.page(remaining) {
                        val page =
                            pages.executePage(
                                if (calls == 3) QueryRunRequest.Resume(first!!) else action,
                                fixture.authority,
                                remaining,
                            )
                        remember(page, calls)
                        page
                    }
                }
                .run(request, budget)
                .refined()
        assertEquals(QueryInvocationStop.NON_ADVANCING, accumulated.stop)
        assertEquals(2, accumulated.items.size)
        assertEquals(2, script.calls)
        val qualified = accumulated.execution as QueryExecutionResult.Qualified
        assertInstanceOf(
            io.github.amichne.kast.query.contract.QueryContinuationState.Terminal::class.java,
            qualified.continuation,
        )
        pages.executePage(QueryRunRequest.Resume(successor!!), fixture.authority, budget)
        script.assertDrained()
    }

    private fun remember(page: QueryPublishedPage, call: Int) {
        val qualified = page as OperationOutcome.Qualified
        when (call) {
            1 -> first = qualified.qualification.progress.continuationToken
            2 -> successor = qualified.qualification.progress.continuationToken
            3 -> Unit
            else -> error("Unexpected page delivery")
        }
    }
}
