package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Production observation and outcome refinement with only the native traversal effect scripted. */
class CallbackBodyScanObservationTest {
    @Test
    fun `exhausted native traversal observes entry before the effect and completion after it`() {
        val counts = BodyScanCounts()
        val outcome =
            observeCallbackBodyScan(counts) {
                assertEquals(listOf(IntellijReadCounter.CALLBACK_BODY_SCANS), counts.values)
                true
            }
        assertEquals(CallbackBodyScanOutcome.EXHAUSTED, outcome)
        assertEquals(
            listOf(IntellijReadCounter.CALLBACK_BODY_SCANS, IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED),
            counts.values,
        )
    }

    @Test
    fun `stopped native traversal retains incomplete observation and typed stopped outcome`() {
        val counts = BodyScanCounts()
        val outcome =
            observeCallbackBodyScan(counts) {
                assertEquals(listOf(IntellijReadCounter.CALLBACK_BODY_SCANS), counts.values)
                false
            }
        assertEquals(CallbackBodyScanOutcome.STOPPED, outcome)
        assertIncomplete(counts)
    }

    @Test
    fun `native cancellation propagates the same instance and records incomplete traversal`() {
        val counts = BodyScanCounts()
        val cancellation = ProcessCanceledException()
        val propagated =
            assertThrows(ProcessCanceledException::class.java) {
                observeCallbackBodyScan(counts) {
                    assertEquals(listOf(IntellijReadCounter.CALLBACK_BODY_SCANS), counts.values)
                    throw cancellation
                }
            }
        assertSame(cancellation, propagated)
        assertIncomplete(counts)
    }

    @Test
    fun `unexpected native failure propagates unchanged and cannot record completion`() {
        val counts = BodyScanCounts()
        val failure = IllegalStateException("native traversal failed")
        val propagated =
            assertThrows(IllegalStateException::class.java) {
                observeCallbackBodyScan(counts) {
                    assertEquals(listOf(IntellijReadCounter.CALLBACK_BODY_SCANS), counts.values)
                    throw failure
                }
            }
        assertSame(failure, propagated)
        assertIncomplete(counts)
    }

    private fun assertIncomplete(counts: BodyScanCounts) {
        assertEquals(
            listOf(IntellijReadCounter.CALLBACK_BODY_SCANS, IntellijReadCounter.CALLBACK_BODY_SCANS_INCOMPLETE),
            counts.values,
        )
    }
}

private class BodyScanCounts : IntellijReadObservation {
    val values = mutableListOf<IntellijReadCounter>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        assertEquals(1, amount)
        values += counter
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
        error("Unexpected body-scan termination: $reason")
}
