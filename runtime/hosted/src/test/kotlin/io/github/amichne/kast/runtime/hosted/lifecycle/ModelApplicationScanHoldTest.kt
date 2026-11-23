package io.github.amichne.kast.runtime.hosted.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelApplicationScanHoldTest {
    private val effects = mutableListOf<String>()
    private val observations = mutableListOf<ModelApplicationScanObservation>()
    private var now = 0L

    private fun hold() =
        ModelApplicationScanHold(
            object : ScanQueue {
                override fun suspendQueue() {
                    effects += "suspend"
                }

                override fun resumeQueue() {
                    effects += "resume"
                }
            },
            { now },
            observations::add,
        )

    @Test
    fun `Gradle resolution leaves the scan queue running`() {
        hold()
        now += 300_000 * NANOS_PER_MILLI
        assertTrue(effects.isEmpty(), "A suspended queue with a pending scan spins in the platform executor")
    }

    @Test
    fun `the queue is held only while the resolved model is applied`() {
        val hold = hold()
        now += 300_000 * NANOS_PER_MILLI
        hold.applicationStarted()
        assertEquals(listOf("suspend"), effects)
        now += 3_200 * NANOS_PER_MILLI
        hold.applicationSettled()
        assertEquals(listOf("suspend", "resume"), effects)
        assertEquals(listOf(ModelApplicationScanObservation(3_200)), observations)
    }

    @Test
    fun `a second application in one import is held again`() {
        val hold = hold()
        repeat(2) {
            hold.applicationStarted()
            hold.applicationSettled()
        }
        assertEquals(listOf("suspend", "resume", "suspend", "resume"), effects)
    }

    @Test
    fun `release frees a held queue once and ignores every later signal`() {
        val hold = hold()
        hold.applicationStarted()
        hold.release()
        hold.release()
        hold.applicationSettled()
        hold.applicationStarted()
        assertEquals(listOf("suspend", "resume"), effects)
        assertEquals(1, observations.size)
    }

    @Test
    fun `release without an application has no queue effect`() {
        hold().release()
        assertTrue(effects.isEmpty())
        assertTrue(observations.isEmpty())
    }

    @Test
    fun `repeated start signals suspend once and keep the first start time`() {
        val hold = hold()
        hold.applicationStarted()
        now += 1_000 * NANOS_PER_MILLI
        hold.applicationStarted()
        now += 1_000 * NANOS_PER_MILLI
        hold.applicationSettled()
        assertEquals(listOf("suspend", "resume"), effects)
        assertEquals(listOf(ModelApplicationScanObservation(2_000)), observations)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
