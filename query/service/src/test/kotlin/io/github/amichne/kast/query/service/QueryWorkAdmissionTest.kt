package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryPredicate
import io.github.amichne.kast.query.contract.QueryStepSyntax
import io.github.amichne.kast.query.contract.QueryVisibilitySelection
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryWorkAdmissionTest {
    @Test
    fun `work alone stops before the first source provider and resumes retained exact evidence`() = runTest {
        verifySourceBoundary(work = 1L, expectedSourceCalls = 0)
    }

    @Test
    fun `work alone publishes one proven source result without starting another provider`() = runTest {
        verifySourceBoundary(work = 2L, expectedSourceCalls = 1)
    }

    private suspend fun verifySourceBoundary(work: Long, expectedSourceCalls: Int) {
        QueryServiceTest().apply {
            val selected = selector(selection())
            var exactCalls = 0
            val sourceGrants = mutableListOf<ResourceBudget>()
            val service =
                service(
                    exact =
                        exactOperations(
                            describe = {
                                exactCalls++
                                SymbolDescriptionResult.Described(SymbolDescription.from(it))
                            },
                            resolve = { error("No discovery expected") },
                        ),
                    source =
                        SourceReadOperations { input ->
                            sourceGrants += input.resources
                            selfRead(selected, DeclarationVisibility.PUBLIC)
                        },
                    clock = QueryNanoClock { 0L },
                )
            val plan = visibilityPairPlan(selected)
            val firstRequest = request(plan, workLimit = work)
            val originalBudget = firstRequest.budget
            val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(firstRequest))

            assertEquals(1, exactCalls)
            assertEquals(expectedSourceCalls, sourceGrants.size)
            assertEquals(listOf(QueryLimitation.WORK_LIMIT_REACHED), first.coverage.limitations)
            assertEquals(List(expectedSourceCalls) { selected }, first.selectors())
            assertSame(originalBudget, firstRequest.budget)
            assertEquals(work, originalBudget.resources.workUnitLimit.value)

            val checkpoint =
                assertInstanceOf(QueryContinuationState.Resumable::class.java, first.continuation).checkpoint
            val larger = request(plan, workLimit = 8L)
            val resumed = QueryExecutionRequest.create(plan, firstRequest.lease, larger.budget, checkpoint).proven()
            val last = assertInstanceOf(QueryExecutionResult.Complete::class.java, service.run(resumed))

            assertEquals(List(2) { selected }, first.selectors() + last.selectors())
            assertEquals(2, exactCalls)
            assertEquals(2, sourceGrants.size)
            sourceGrants.forEach { grant ->
                assertEquals(1L, grant.workUnitLimit.value)
                assertEquals(1, grant.resultLimit.value)
                assertEquals(originalBudget.resources.elapsedTimeLimit, grant.elapsedTimeLimit)
            }
            assertSame(larger.budget, resumed.budget)
            assertEquals(work, originalBudget.resources.workUnitLimit.value)
        }
    }

    private fun QueryServiceTest.visibilityPairPlan(selected: SymbolSelector): AdmittedQueryPlan =
        exactReferencePlan(
            List(2) { selected },
            listOf(
                QueryStepSyntax.Where(
                    QueryPredicate.Visibility(
                        QueryVisibilitySelection.from(setOf(DeclarationVisibility.PUBLIC)).proven()
                    )
                )
            ),
        )
}

private fun QueryExecutionResult.selectors() =
    when (this) {
        is QueryExecutionResult.Complete -> result.items.map { it.selector }
        is QueryExecutionResult.Qualified -> result.items.map { it.selector }
        is QueryExecutionResult.Rejected -> error("Unexpected rejection: $reason")
    }

private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
