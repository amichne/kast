package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointStorageAdmission
import io.github.amichne.kast.query.contract.QueryCheckpointStorageBytes
import io.github.amichne.kast.query.contract.QueryCheckpointStorageEstimate
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGauge
import io.github.amichne.kast.workspace.intellij.read.IntellijReadGaugeValue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedQueryCheckpointStorageObservationTest {
    @Test
    fun `last rejection components survive a later admitted checkpoint`() {
        val observation = Observation()
        val adapter = hostedQueryCheckpointStorageObservation(observation)
        val estimate = QueryCheckpointStorageEstimate(bytes(11), bytes(13), bytes(17), bytes(19), bytes(23))
        adapter.observe(QueryCheckpointStorageAdmission.evaluate(estimate, QueryByteLimit.parse(80).refined()))
        adapter.observe(QueryCheckpointStorageAdmission.evaluate(estimate, QueryByteLimit.parse(83).refined()))
        assertEquals(
            listOf(
                IntellijReadCounter.QUERY_CHECKPOINT_CAPACITY_EXCEEDED,
                IntellijReadCounter.QUERY_CHECKPOINT_WITHIN_LIMIT,
            ),
            observation.counters,
        )
        assertEquals(21, observation.measurements)
        assertEquals(
            mapOf(
                IntellijReadGauge.QUERY_CHECKPOINT_ALLOWANCE to 83L,
                IntellijReadGauge.QUERY_CHECKPOINT_REQUIRED_BYTES to 83L,
                IntellijReadGauge.QUERY_CHECKPOINT_TASK_BYTES to 11L,
                IntellijReadGauge.QUERY_CHECKPOINT_IDENTITY_ROW_BYTES to 13L,
                IntellijReadGauge.QUERY_CHECKPOINT_INPUT_BYTES to 17L,
                IntellijReadGauge.QUERY_CHECKPOINT_IMPACT_BYTES to 19L,
                IntellijReadGauge.QUERY_CHECKPOINT_JOIN_BYTES to 23L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_ALLOWANCE to 80L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_REQUIRED_BYTES to 83L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_TASK_BYTES to 11L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_IDENTITY_ROW_BYTES to 13L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_INPUT_BYTES to 17L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_IMPACT_BYTES to 19L,
                IntellijReadGauge.QUERY_CHECKPOINT_REJECTION_JOIN_BYTES to 23L,
            ),
            observation.gauges,
        )
    }

    private class Observation : IntellijReadObservation by IntellijReadObservation.None {
        val gauges = linkedMapOf<IntellijReadGauge, Long>()
        val counters = mutableListOf<IntellijReadCounter>()
        var measurements = 0

        override fun measure(gauge: IntellijReadGauge, value: IntellijReadGaugeValue) {
            measurements++
            gauges[gauge] = value.value
        }

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(IntellijReadContributor.NONE, contributor)
            assertEquals(1, amount)
            counters += counter
        }
    }

    private fun bytes(value: Long) = QueryCheckpointStorageBytes.parse(value).refined()
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
