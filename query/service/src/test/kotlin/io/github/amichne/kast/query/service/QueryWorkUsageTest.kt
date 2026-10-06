package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryWorkUsageTest {
    @Test
    fun `real evaluator receipts debit revalidation once across resumed pages`() = runTest {
        val fixture = QueryServiceTest()
        val selector = fixture.selector(fixture.selection())
        var descriptions = 0
        val service =
            fixture.service(
                exact =
                    fixture.exactOperations(
                        describe = {
                            descriptions++
                            SymbolDescriptionResult.Described(SymbolDescription.from(it))
                        },
                        resolve = { error("Discovery must not execute") },
                    ),
                clock = QueryNanoClock { 0L },
            )
        val request =
            fixture.request(fixture.exactReferencePlan(listOf(selector, selector)), workLimit = 2, resultLimit = 1)
        val first = service.run(request) as QueryExecutionResult.Qualified
        assertEquals(1L, (first.workUsage as QueryWorkUsage.Observed).count.value)
        val checkpoint =
            (first.continuation as io.github.amichne.kast.query.contract.QueryContinuationState.Resumable).checkpoint
        val resumed =
            io.github.amichne.kast.query.contract.QueryExecutionRequest.create(
                request.plan,
                request.lease,
                request.budget,
                checkpoint,
            ) as io.github.amichne.kast.kernel.Refinement.Refined
        val last = service.run(resumed.value) as QueryExecutionResult.Complete
        assertEquals(1L, (last.workUsage as QueryWorkUsage.Observed).count.value)
        assertEquals(2, descriptions)
        assertEquals(2, last.coverage.resultCount.value)
    }
}
