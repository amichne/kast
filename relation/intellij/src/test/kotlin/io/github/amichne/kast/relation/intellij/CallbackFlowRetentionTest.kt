package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Aggregate retention limits are independent of alias, invocation, and owner collection sizes. */
class CallbackFlowRetentionTest {
    @Test
    fun `alias and invocation share one result grant`() {
        val retention = CallbackFlowRetention(budget(results = 1))
        assertTrue(retention.admit(128L) is Refinement.Refined) // first alias
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED),
            retention.admit(4096L),
        ) // first invocation, aliases already occupy the grant
    }

    @Test
    fun `mixed proof categories exhaust one aggregate grant and remain rejected`() {
        val retention = CallbackFlowRetention(budget(results = 2))
        assertTrue(retention.admit(128L) is Refinement.Refined)
        assertTrue(retention.admit(4096L) is Refinement.Refined)
        repeat(3) {
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED),
                retention.admit(4096L),
            )
        }
    }

    @Test
    fun `byte rejection consumes neither a result slot nor retained bytes`() {
        val retention = CallbackFlowRetention(budget(results = 2, bytes = 10L))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(11L))
        assertTrue(retention.admit(5L) is Refinement.Refined)
        assertTrue(retention.admit(5L) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED), retention.admit(0L))
    }

    @Test
    fun `exact byte boundary admits a prefix and rejects overflow without charging it`() {
        val retention = CallbackFlowRetention(budget(results = 3, bytes = 10L))
        assertTrue(retention.admit(5L) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(6L))
        assertTrue(retention.admit(5L) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(1L))
    }

    @Test
    fun `a separate callback read owns a fresh allowance`() {
        repeat(2) {
            val retention = CallbackFlowRetention(budget(results = 1))
            assertTrue(retention.admit(128L) is Refinement.Refined)
            assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED), retention.admit(1L))
        }
    }

    private fun budget(results: Int, bytes: Long = 100000L) =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(results).value(),
                WorkUnitLimit.parse(100L).value(),
                ElapsedTimeLimitMillis.parse(1000L).value(),
            ),
            RelationByteLimit.parse(bytes).value(),
        )

    private fun <V, F> Refinement<V, F>.value(): V = (this as Refinement.Refined).value
}
