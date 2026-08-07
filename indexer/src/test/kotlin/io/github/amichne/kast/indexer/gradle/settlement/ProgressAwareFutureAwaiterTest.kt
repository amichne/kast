package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProgressAwareFutureAwaiterTest {
    @Test
    fun `completed future samples elapsed and no-progress at one instant`() {
        var now = 0L
        val awaiter = ProgressAwareFutureAwaiter(
            noProgressTimeout = Duration.ofMillis(2),
            maximumWait = Duration.ofMillis(6),
            observationInterval = Duration.ofMillis(1),
            nanoTime = { ++now },
            pause = {},
        )

        val evidence = awaiter.await(
            RuntimeProgressStage.GRADLE_IMPORT,
            CompletableFuture.completedFuture(null),
            Supplier { "complete" },
            BooleanSupplier { false },
        )

        assertEquals(evidence.elapsed, evidence.noProgress)
    }

    @Test
    fun `changing progress may exceed the no-progress window`() {
        var now = 0L
        var observation = 0
        val future = CompletableFuture<Void>()
        val awaiter = ProgressAwareFutureAwaiter(
            noProgressTimeout = Duration.ofMillis(2),
            maximumWait = Duration.ofMillis(6),
            observationInterval = Duration.ofMillis(1),
            nanoTime = { now * 1_000_000 },
            pause = {
                now += it.toMillis()
                observation += 1
                if (now == 5L) future.complete(null)
            },
        )

        val evidence = awaiter.await(
            RuntimeProgressStage.GRADLE_IMPORT,
            future,
            Supplier { observation },
            BooleanSupplier { false },
        )

        assertEquals(Duration.ofMillis(5), evidence.elapsed)
    }

    @Test
    fun `stalled progress fails at the no-progress deadline with typed evidence`() {
        var now = 0L
        val awaiter = ProgressAwareFutureAwaiter(
            noProgressTimeout = Duration.ofMillis(2),
            maximumWait = Duration.ofMillis(6),
            observationInterval = Duration.ofMillis(1),
            nanoTime = { now * 1_000_000 },
            pause = { now += it.toMillis() },
        )

        val error = assertThrows(RuntimeProgressDeadlineExceeded::class.java) {
            awaiter.await(
                RuntimeProgressStage.MODEL_SETTLEMENT,
                CompletableFuture(),
                Supplier { "unchanged" },
                BooleanSupplier { false },
            )
        }

        assertEquals(Duration.ofMillis(2), error.evidence.noProgress)
    }
}
