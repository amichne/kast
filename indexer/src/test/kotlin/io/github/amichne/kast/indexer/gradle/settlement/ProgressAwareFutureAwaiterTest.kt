package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import java.time.Duration
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProgressAwareFutureAwaiterTest {
    @Test
    fun `completed future samples elapsed and no-progress at one instant`() {
        var now = 0L
        val awaiter = ProgressAwareFutureAwaiter(
            policy = policy(),
            clock = MonotonicClock.fromRaw { ++now },
            pause = {},
        )

        val outcome = awaiter.await(
            RuntimeProgressStage.GRADLE_IMPORT,
            CompletableFuture.completedFuture(null),
            RuntimeProgressProbe { RuntimeProgressObservation.capture("complete") },
            RuntimeWaitLifecycleProbe { RuntimeWaitLifecycle.Active },
        )
        val evidence = assertInstanceOf(RuntimeProgressAwaitOutcome.Completed::class.java, outcome).evidence

        assertEquals(evidence.elapsed, evidence.noProgress)
    }

    @Test
    fun `changing progress may exceed the no-progress window`() {
        var now = 0L
        var observation = 0
        val future = CompletableFuture<Void>()
        val awaiter = ProgressAwareFutureAwaiter(
            policy = policy(),
            clock = MonotonicClock.fromRaw { now * 1_000_000 },
            pause = {
                now += it.toMillis()
                observation += 1
                if (now == 5L) future.complete(null)
            },
        )

        val outcome = awaiter.await(
            RuntimeProgressStage.GRADLE_IMPORT,
            future,
            RuntimeProgressProbe { RuntimeProgressObservation.capture(observation) },
            RuntimeWaitLifecycleProbe { RuntimeWaitLifecycle.Active },
        )
        val evidence = assertInstanceOf(RuntimeProgressAwaitOutcome.Completed::class.java, outcome).evidence

        assertEquals(Duration.ofMillis(5), evidence.elapsed)
    }

    @Test
    fun `stalled progress fails at the no-progress deadline with typed evidence`() {
        var now = 0L
        val awaiter = ProgressAwareFutureAwaiter(
            policy = policy(),
            clock = MonotonicClock.fromRaw { now * 1_000_000 },
            pause = { now += it.toMillis() },
        )

        val outcome = awaiter.await(
            RuntimeProgressStage.MODEL_SETTLEMENT,
            CompletableFuture(),
            RuntimeProgressProbe { RuntimeProgressObservation.capture("unchanged") },
            RuntimeWaitLifecycleProbe { RuntimeWaitLifecycle.Active },
        )
        val rejected = assertInstanceOf(RuntimeProgressAwaitOutcome.Rejected::class.java, outcome)
        val failure = assertInstanceOf(RuntimeProgressAwaitFailure.DeadlineExceeded::class.java, rejected.failure)

        assertEquals(Duration.ofMillis(2), failure.evidence.noProgress)
    }

    private fun policy(): RuntimeProgressWaitPolicy = RuntimeProgressWaitPolicy.derive(
        noProgressTimeout = Duration.ofMillis(2),
        maximumWait = Duration.ofMillis(6),
        observationInterval = Duration.ofMillis(1),
    )
}
