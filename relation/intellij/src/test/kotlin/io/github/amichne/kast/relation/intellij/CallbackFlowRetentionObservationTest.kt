package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackFlowRetentionObservationTest {
    @Test
    fun `proof storage beyond the output allowance emits measurements without byte rejection`() {
        val observed = Observation()
        val retention = CallbackFlowRetention(budget(results = 3, bytes = 10).resources.resultLimit, observed)
        assertEquals(Refinement.Refined(Unit), retention.admit(5))
        assertEquals(Refinement.Refined(Unit), retention.admit(6))
        assertEquals(11L, observed.last(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES))
        assertEquals(11L, observed.last(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES))
        assertTrue(observed.values(IntellijReadGauge.CALLBACK_PROOF_BYTE_ALLOWANCE).isEmpty())
        assertTrue(observed.values(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES).isEmpty())
        assertEquals(List(2) { IntellijReadCounter.CALLBACK_PROOF_RETENTION_ADMITTED }, observed.counts)
    }

    @Test
    fun `result rejection keeps its prefix and emits the uncharged diagnostic estimate`() {
        val observed = Observation()
        val retention = CallbackFlowRetention(budget(results = 1, bytes = 10).resources.resultLimit, observed)
        assertTrue(retention.admit(4) is Refinement.Refined)
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED), retention.admit(8))
        assertEquals(4L, observed.last(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES))
        assertEquals(12L, observed.last(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES))
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_PROOF_RETENTION_ADMITTED,
                IntellijReadCounter.CALLBACK_PROOF_RETENTION_RESULT_REJECTED,
            ),
            observed.counts,
        )
        assertTrue(observed.values(IntellijReadGauge.CALLBACK_PROOF_BYTE_REJECTION_REQUIRED_BYTES).isEmpty())
    }

    @Test
    fun `separate retention ledgers emit their own estimates without summing them`() {
        val observed = Observation()
        val first = CallbackFlowRetention(budget(results = 2, bytes = 10).resources.resultLimit, observed)
        assertEquals(Refinement.Refined(Unit), first.admit(8))
        assertEquals(Refinement.Refined(Unit), first.admit(3))
        assertEquals(11L, observed.last(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES))
        assertEquals(
            Refinement.Refined(Unit),
            CallbackFlowRetention(budget(results = 1, bytes = 5).resources.resultLimit, observed).admit(3),
        )
        assertEquals(3L, observed.last(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES))
        assertEquals(3L, observed.last(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES))
    }

    @Test
    fun `diagnostic byte estimates saturate overflow without rejecting a proof`() {
        val observed = Observation()
        val retention = CallbackFlowRetention(budget(results = 2, bytes = 10).resources.resultLimit, observed)
        assertTrue(retention.admit(1) is Refinement.Refined)
        assertEquals(
            Refinement.Refined(Unit),
            retention.admit(Long.MAX_VALUE),
        )
        assertEquals(Long.MAX_VALUE, observed.last(IntellijReadGauge.CALLBACK_PROOF_REQUIRED_BYTES))
        assertEquals(Long.MAX_VALUE, observed.last(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES))
    }

    @Test
    fun `summary retention uses the supplied native observation`() {
        val observed = Observation()
        val summaries = CallbackParameterSummaries(budget(results = 1, bytes = 10), observed)
        assertTrue(summaries.retention.admit(5) is Refinement.Refined)
        assertEquals(5L, observed.last(IntellijReadGauge.CALLBACK_PROOF_RETAINED_BYTES))
        assertEquals(listOf(IntellijReadCounter.CALLBACK_PROOF_RETENTION_ADMITTED), observed.counts)
    }

    private class Observation : IntellijReadObservation {
        val counts = mutableListOf<IntellijReadCounter>()
        private val measurements = mutableListOf<Pair<IntellijReadGauge, Long>>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(1, amount)
            assertEquals(IntellijReadContributor.NONE, contributor)
            counts += counter
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            error("Retention must not invent native termination")
        }

        override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
            measurements += gauge to value.value
        }

        fun values(gauge: IntellijReadGauge) = measurements.filter { it.first == gauge }.map { it.second }

        fun last(gauge: IntellijReadGauge) = values(gauge).lastOrNull()
    }

    private fun budget(results: Int, bytes: Long) =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(results).proven(),
                WorkUnitLimit.parse(100).proven(),
                ElapsedTimeLimitMillis.parse(1000).proven(),
            ),
            RelationByteLimit.parse(bytes).proven(),
        )

    private fun <V, F> Refinement<V, F>.proven(): V = (this as Refinement.Refined).value
}
