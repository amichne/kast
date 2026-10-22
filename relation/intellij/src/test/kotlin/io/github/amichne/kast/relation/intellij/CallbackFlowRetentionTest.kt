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

/** Internal proof storage does not consume the allowance for serialized output. */
class CallbackFlowRetentionTest {
    @Test
    fun `runner proof storage has no aggregate result or byte quota`() {
        val grant =
            RelationBudget(
                ResourceBudget.RunnerControlled,
                RelationByteLimit.parse(1L).value(),
                ResultLimit.parse(1).value(),
            )
        val retention = CallbackParameterSummaries(grant).retention
        repeat(10_000) { assertEquals(Refinement.Refined(Unit), retention.admit(100_000L)) }
    }

    @Test
    fun `runner callback work has no work or elapsed stop`() {
        var now = 0L
        val allowance = IntellijRelationAllowance { now }
        repeat(200) {
            now += 1_000_000_000L
            assertEquals(CallbackWorkAdmission.READY, allowance.admitCallbackWork(ResourceBudget.RunnerControlled))
        }
        assertEquals(200L, allowance.examined)
        assertEquals(
            CallbackWorkAdmission.TIME_LIMIT_REACHED,
            allowance.admitCallbackWork(budget(results = 1).resources),
        )
    }

    @Test
    fun `alias and invocation share one result grant`() {
        val retention = CallbackFlowRetention(budget(results = 1).resources.resultAllowance)
        assertTrue(retention.admit(128L) is Refinement.Refined) // first alias
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED),
            retention.admit(4096L),
        ) // first invocation, aliases already occupy the grant
    }

    @Test
    fun `mixed proof categories exhaust one aggregate grant and remain rejected`() {
        val retention = CallbackFlowRetention(budget(results = 2).resources.resultAllowance)
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
    fun `proof records can exceed the output byte allowance`() {
        val retention = CallbackParameterSummaries(budget(results = 2, bytes = 10L)).retention
        assertEquals(Refinement.Refined(Unit), retention.admit(11L))
        assertEquals(Refinement.Refined(Unit), retention.admit(100_000L))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED), retention.admit(0L))
    }

    @Test
    fun `proof admissions do not depend on the output page size`() {
        val retention = CallbackParameterSummaries(budget(results = 3, bytes = 10L)).retention
        assertTrue(retention.admit(5L) is Refinement.Refined)
        assertEquals(Refinement.Refined(Unit), retention.admit(6L))
        assertTrue(retention.admit(5L) is Refinement.Refined)
    }

    @Test
    fun `a separate callback read owns a fresh allowance`() {
        repeat(2) {
            val retention = CallbackFlowRetention(budget(results = 1).resources.resultAllowance)
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
