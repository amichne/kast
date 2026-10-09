package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.protocol.QueryResultRetentionEvidence
import io.github.amichne.kast.query.protocol.QueryResultRetentionIssue
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedQueryResultRetentionObservationTest {
    @Test
    fun `the sole capture owner retains bounded started and captured counters`() {
        val observation = Observation()
        val adapter = hostedQueryResultRetentionObservation(observation)
        adapter.observe(QueryResultRetentionEvidence.CaptureStarted)
        adapter.observe(QueryResultRetentionEvidence.Captured)
        assertEquals(
            listOf(
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_STARTED,
                IntellijReadCounter.QUERY_RETENTION_CAPTURED,
            ),
            observation.counters,
        )
        assertEquals(List(2) { IntellijReadPhase.RETENTION }, observation.phases)
    }

    @Test
    fun `all finite capture rejections preserve their exact bounded diagnostic counter`() {
        val observation = Observation()
        val adapter = hostedQueryResultRetentionObservation(observation)
        QueryRetainedResultFailure.entries.forEach { adapter.observe(QueryResultRetentionEvidence.CaptureRejected(it)) }
        assertEquals(
            listOf(
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_EXECUTION_REJECTED,
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_PRESENTATION_ONLY_ROWS,
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_BASIS_MISMATCH,
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_INCONSISTENT_COVERAGE,
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_UNKNOWN_ROW,
                IntellijReadCounter.QUERY_RETENTION_CAPTURE_DUPLICATE_ROW,
            ),
            observation.counters,
        )
        assertEquals(List(6) { IntellijReadPhase.RETENTION }, observation.phases)
    }

    @Test
    fun `issued unavailable and capacity outcomes stay distinct after the issuance effect`() {
        val observation = Observation()
        val adapter = hostedQueryResultRetentionObservation(observation)
        QueryResultRetentionIssue.entries.forEach { adapter.observe(QueryResultRetentionEvidence.Issuance(it)) }
        assertEquals(
            listOf(
                IntellijReadCounter.QUERY_RETENTION_RESULT_ISSUED,
                IntellijReadCounter.QUERY_RETENTION_RESULT_UNAVAILABLE,
                IntellijReadCounter.QUERY_RETENTION_RESULT_CAPACITY_EXCEEDED,
            ),
            observation.counters,
        )
        assertEquals(List(3) { IntellijReadPhase.RETENTION }, observation.phases)
    }

    private class Observation : IntellijReadObservation by IntellijReadObservation.None {
        val phases = mutableListOf<IntellijReadPhase>()
        val counters = mutableListOf<IntellijReadCounter>()

        override fun phase(value: IntellijReadPhase) {
            phases += value
        }

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(IntellijReadContributor.NONE, contributor)
            assertEquals(1, amount)
            counters += counter
        }
    }
}
