package io.github.amichne.kast.headless

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadlessGradleModelSettlementAwaiterTest {
    @Test
    fun `scheduled import progresses through indexing and settles after ten stable observations`() {
        val observations =
            listOf(
                observation(reload = HeadlessGradleReloadState.SCHEDULED),
                observation(
                    reload = HeadlessGradleReloadState.IN_PROGRESS,
                    resolve = HeadlessGradleResolveState.IN_PROGRESS,
                ),
                observation(index = HeadlessIdeaIndexState.DUMB),
            ) + List(10) { observation() }
        val harness = SettlementHarness(observations)
        val observer: () -> HeadlessGradleImportObservation = harness::observe

        val outcome = harness.awaiter(stableObservations = 10).await(observer)

        val settled = assertInstanceOf(HeadlessGradleModelSettlementOutcome.Settled::class.java, outcome)
        assertEquals(13, settled.evidence.totalObservations)
        assertEquals(10, settled.evidence.stableObservations)
        assertEquals(observation(), settled.evidence.lastObservation)
        assertTrue(settled.evidence.totalTransitions > 0)
    }

    @Test
    fun `constant in-progress import times out without transitions and preserves its last state`() {
        val stalled =
            observation(
                reload = HeadlessGradleReloadState.IN_PROGRESS,
                resolve = HeadlessGradleResolveState.IN_PROGRESS,
                index = HeadlessIdeaIndexState.DUMB,
            )
        val harness = SettlementHarness(listOf(stalled))

        val outcome = harness.awaiter(timeoutMillis = 3).await(harness::observe)

        val timedOut = assertInstanceOf(HeadlessGradleModelSettlementOutcome.TimedOut::class.java, outcome)
        assertEquals(stalled, timedOut.evidence.lastObservation)
        assertEquals(0, timedOut.evidence.totalTransitions)
    }

    @Test
    fun `changing import times out with transition evidence`() {
        val first = observation(reload = HeadlessGradleReloadState.SCHEDULED)
        val second =
            observation(
                reload = HeadlessGradleReloadState.IN_PROGRESS,
                resolve = HeadlessGradleResolveState.IN_PROGRESS,
            )
        val harness = SettlementHarness(listOf(first, second))

        val outcome = harness.awaiter(timeoutMillis = 4).await(harness::observe)

        val timedOut = assertInstanceOf(HeadlessGradleModelSettlementOutcome.TimedOut::class.java, outcome)
        assertTrue(timedOut.evidence.totalTransitions > 0)
    }

    @Test
    fun `timeout exception renders the last observed import state`() {
        val stalled =
            observation(
                reload = HeadlessGradleReloadState.IN_PROGRESS,
                resolve = HeadlessGradleResolveState.IN_PROGRESS,
                index = HeadlessIdeaIndexState.DUMB,
            )
        val harness = SettlementHarness(listOf(stalled))
        val outcome = harness.awaiter(timeoutMillis = 2).await(harness::observe)

        val error = HeadlessGradleModelSettlementException(outcome)
        val message = error.message.orEmpty()

        assertTrue(message.contains("lastObservation=$stalled"))
        assertTrue(message.contains("totalTransitions=0"))
        assertFalse(message.contains("transitionProgress"))
    }

    @Test
    fun `a noncandidate observation resets the stable counter`() {
        val ready = observation()
        val busy = observation(resolve = HeadlessGradleResolveState.IN_PROGRESS)
        val harness = SettlementHarness(listOf(ready, busy, ready, ready))

        val outcome = harness.awaiter(stableObservations = 2).await(harness::observe)

        val settled = assertInstanceOf(HeadlessGradleModelSettlementOutcome.Settled::class.java, outcome)
        assertEquals(4, settled.evidence.totalObservations)
        assertEquals(2, settled.evidence.stableObservations)
    }

    @Test
    fun `transition trace remains bounded and retains the final observation`() {
        val scheduled = observation(reload = HeadlessGradleReloadState.SCHEDULED)
        val resolving = observation(resolve = HeadlessGradleResolveState.IN_PROGRESS)
        val harness = SettlementHarness(listOf(scheduled, resolving))

        val outcome =
            harness
                .awaiter(timeoutMillis = 6, maxTransitionTraceEntries = 2)
                .await(harness::observe)

        val timedOut = assertInstanceOf(HeadlessGradleModelSettlementOutcome.TimedOut::class.java, outcome)
        assertEquals(2, timedOut.evidence.recentTransitions.size)
        assertEquals(timedOut.evidence.lastObservation, timedOut.evidence.recentTransitions.last().observation)
        assertTrue(timedOut.evidence.totalTransitions > timedOut.evidence.recentTransitions.size.toLong())
    }

    @Test
    fun `interruption preserves the thread flag and returns typed evidence`() {
        val harness = SettlementHarness(listOf(observation(resolve = HeadlessGradleResolveState.IN_PROGRESS)))
        val awaiter =
            HeadlessGradleModelSettlementAwaiter(
                policy = policy(),
                nanoTime = harness::nanoTime,
                pause = { throw InterruptedException("test interruption") },
            )

        try {
            val outcome = awaiter.await(harness::observe)

            assertInstanceOf(HeadlessGradleModelSettlementOutcome.Interrupted::class.java, outcome)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `disposed project terminates immediately with typed evidence`() {
        val disposed = observation(lifecycle = HeadlessProjectLifecycleState.DISPOSED)
        val harness = SettlementHarness(listOf(disposed))

        val outcome = harness.awaiter().await(harness::observe)

        val failed = assertInstanceOf(HeadlessGradleModelSettlementOutcome.ProjectDisposed::class.java, outcome)
        assertEquals(disposed, failed.evidence.lastObservation)
        assertEquals(1, failed.evidence.totalObservations)
    }

    @Test
    fun `representative transition sequence settles deterministically`() {
        val harness =
            SettlementHarness(
                listOf(
                    observation(reload = HeadlessGradleReloadState.SCHEDULED),
                    observation(
                        reload = HeadlessGradleReloadState.IN_PROGRESS,
                        resolve = HeadlessGradleResolveState.IN_PROGRESS,
                    ),
                    observation(index = HeadlessIdeaIndexState.DUMB),
                    observation(),
                    observation(),
                ),
            )

        val outcome = harness.awaiter(stableObservations = 2).await(harness::observe)

        assertInstanceOf(HeadlessGradleModelSettlementOutcome.Settled::class.java, outcome)
    }

    private fun policy(
        timeoutMillis: Long = 100,
        stableObservations: Int = 2,
        maxTransitionTraceEntries: Int = 16,
    ) =
        HeadlessGradleModelSettlementPolicy(
            timeout = Duration.ofMillis(timeoutMillis),
            observationInterval = Duration.ofMillis(1),
            requiredStableObservations = stableObservations,
            maxTransitionTraceEntries = maxTransitionTraceEntries,
        )

    private fun SettlementHarness.awaiter(
        timeoutMillis: Long = 100,
        stableObservations: Int = 2,
        maxTransitionTraceEntries: Int = 16,
    ) =
        HeadlessGradleModelSettlementAwaiter(
            policy = policy(timeoutMillis, stableObservations, maxTransitionTraceEntries),
            nanoTime = ::nanoTime,
            pause = ::pause,
        )

    private fun observation(
        reload: HeadlessGradleReloadState = HeadlessGradleReloadState.COMPLETED,
        resolve: HeadlessGradleResolveState = HeadlessGradleResolveState.IDLE,
        index: HeadlessIdeaIndexState = HeadlessIdeaIndexState.SMART,
        lifecycle: HeadlessProjectLifecycleState = HeadlessProjectLifecycleState.ACTIVE,
    ) = HeadlessGradleImportObservation(reload, resolve, index, lifecycle)
}

private class SettlementHarness(
    private val observations: List<HeadlessGradleImportObservation>,
) {
    private var observationIndex: Int = 0
    private var currentNanos: Long = 0

    init {
        require(observations.isNotEmpty())
    }

    fun observe(): HeadlessGradleImportObservation {
        val observation = observations[observationIndex % observations.size]
        observationIndex += 1
        return observation
    }

    fun nanoTime(): Long = currentNanos

    fun pause(duration: Duration) {
        currentNanos += duration.toNanos()
    }
}
