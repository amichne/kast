package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContainerCancellationTest {
    @Test
    fun `an accepted signal waits for the owned transport exit`() {
        val effects =
            ScriptedCancellation(
                listOf(ContainerSignalAttempt.ACCEPTED),
                emptyList(),
                listOf(ContainerCancellationWait.EXITED),
            )
        assertEquals(ContainerCancellationOutcome.TRANSPORT_EXIT_OBSERVED, cancelContainerExecution(effects))
        effects.exhausted()
    }

    @Test
    fun `early registration rejection retries only while the owned transport lives`() {
        val effects =
            ScriptedCancellation(
                listOf(ContainerSignalAttempt.REJECTED, ContainerSignalAttempt.ACCEPTED),
                listOf(ContainerCancellationWait.RUNNING),
                listOf(ContainerCancellationWait.EXITED),
            )
        assertEquals(ContainerCancellationOutcome.TRANSPORT_EXIT_OBSERVED, cancelContainerExecution(effects))
        effects.exhausted()
    }

    @Test
    fun `signal acceptance alone does not claim that the execution exited`() {
        val effects =
            ScriptedCancellation(
                listOf(ContainerSignalAttempt.ACCEPTED),
                emptyList(),
                listOf(ContainerCancellationWait.RUNNING),
            )
        assertEquals(ContainerCancellationOutcome.EXECUTION_STILL_RUNNING, cancelContainerExecution(effects))
        effects.exhausted()
    }

    @Test
    fun `a transport exit before registration stops retries without a settlement claim`() {
        val effects =
            ScriptedCancellation(
                listOf(ContainerSignalAttempt.REJECTED),
                listOf(ContainerCancellationWait.EXITED),
                emptyList(),
            )
        assertEquals(ContainerCancellationOutcome.TRANSPORT_EXITED_BEFORE_SIGNAL, cancelContainerExecution(effects))
        effects.exhausted()
    }

    @Test
    fun `the sender deadline rejects without another effect`() {
        val effects = ScriptedCancellation(listOf(ContainerSignalAttempt.DEADLINE_EXCEEDED), emptyList(), emptyList())
        assertEquals(ContainerCancellationOutcome.DEADLINE_EXCEEDED, cancelContainerExecution(effects))
        effects.exhausted()
    }

    @Test
    fun `repeated rejection has a finite attempt budget`() {
        val effects =
            ScriptedCancellation(
                List(10) { ContainerSignalAttempt.REJECTED },
                List(9) { ContainerCancellationWait.RUNNING },
                emptyList(),
            )
        assertEquals(ContainerCancellationOutcome.RETRY_EXHAUSTED, cancelContainerExecution(effects))
        effects.exhausted()
    }

    private class ScriptedCancellation(
        attempts: List<ContainerSignalAttempt>,
        retries: List<ContainerCancellationWait>,
        exits: List<ContainerCancellationWait>,
    ) : ContainerCancellationEffects {
        private val attempts = ArrayDeque(attempts)
        private val retries = ArrayDeque(retries)
        private val exits = ArrayDeque(exits)

        override fun send(): ContainerSignalAttempt = attempts.removeFirst()

        override fun awaitRetry(): ContainerCancellationWait = retries.removeFirst()

        override fun awaitExit(): ContainerCancellationWait = exits.removeFirst()

        fun exhausted() {
            assertTrue(attempts.isEmpty())
            assertTrue(retries.isEmpty())
            assertTrue(exits.isEmpty())
        }
    }
}
