package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryLimitation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunnerControlledImpactAllowanceTest {
    @Test
    fun `runner admits a native proof larger than its output and checkpoint targets`() {
        val fixture = QueryImpactExecutionFixture()
        val bounded = fixture.request(work = 1, results = 1, checkpointBytes = 1, returnedBytes = 1)
        val request =
            when (
                val admitted =
                    QueryExecutionRequest.create(
                        bounded.plan,
                        bounded.lease,
                        bounded.budget.copy(resources = ResourceBudget.RunnerControlled),
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("The case preserves the admitted plan and lease")
            }
        val state = QueryExecutionState(request, QueryNanoClock { 0L })
        val grant =
            assertInstanceOf(
                io.github.amichne.kast.relation.contract.RelationBudget::class.java,
                state.impactBudget(0L, 100_000_000L),
            )
        assertEquals(ResourceBudget.RunnerControlled, grant.resources)
        assertEquals(1, grant.outputResults.value)
        assertTrue(grant.permitsReturnedBytes(100_000_000L))
        assertTrue(state.limitations.isEmpty())
    }

    @Test
    fun `bounded grant rejects the same internal proof without more storage`() {
        val fixture = QueryImpactExecutionFixture()
        val request = fixture.request(work = 1, results = 1, checkpointBytes = 1, returnedBytes = 1)
        val state = QueryExecutionState(request, QueryNanoClock { 0L })
        assertNull(state.impactBudget(0L, 100_000_000L))
        assertEquals(setOf(QueryLimitation.RESULT_LIMIT_REACHED), state.limitations)
    }

    @Test
    fun `runner work measurements saturate without a synthetic execution stop`() {
        val fixture = QueryImpactExecutionFixture()
        val bounded = fixture.request()
        val request =
            when (
                val admitted =
                    QueryExecutionRequest.create(
                        bounded.plan,
                        bounded.lease,
                        bounded.budget.copy(resources = ResourceBudget.RunnerControlled),
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> error("The case preserves the admitted plan and lease")
            }
        val state = QueryExecutionState(request, QueryNanoClock { 0L })
        state.consume(Long.MAX_VALUE)
        assertTrue(state.consumeUnit())
        assertEquals(Long.MAX_VALUE, state.consumedWork().value)
        assertTrue(state.limitations.isEmpty())
    }
}
