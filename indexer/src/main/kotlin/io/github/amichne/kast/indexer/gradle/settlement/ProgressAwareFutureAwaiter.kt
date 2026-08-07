package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Supplier

data class RuntimeProgressDeadlineEvidence(
    val stage: RuntimeProgressStage,
    val elapsed: Duration,
    val noProgress: Duration,
) {
    init {
        require(!elapsed.isNegative) { "elapsed must not be negative" }
        require(!noProgress.isNegative && noProgress <= elapsed) {
            "noProgress must be within elapsed"
        }
    }
}

class RuntimeProgressDeadlineExceeded(
    val evidence: RuntimeProgressDeadlineEvidence,
) : IllegalStateException(
    "Runtime progress deadline exceeded: stage=${evidence.stage}, " +
        "elapsed=${evidence.elapsed}, noProgress=${evidence.noProgress}",
)

class ProgressAwareFutureAwaiter internal constructor(
    private val noProgressTimeout: Duration,
    private val maximumWait: Duration,
    private val observationInterval: Duration,
    private val nanoTime: () -> Long,
    private val pause: (Duration) -> Unit,
) {
    init {
        require(!noProgressTimeout.isNegative && !noProgressTimeout.isZero)
        require(maximumWait >= noProgressTimeout)
        require(!observationInterval.isNegative && !observationInterval.isZero)
    }

    @Throws(InterruptedException::class, ExecutionException::class)
    fun await(
        stage: RuntimeProgressStage,
        future: CompletableFuture<Void>,
        observation: Supplier<Any>,
        disposed: BooleanSupplier,
    ): RuntimeProgressDeadlineEvidence {
        val startedAt = nanoTime()
        var lastProgressAt = startedAt
        var lastObservation: Any? = null
        while (!future.isDone) {
            if (disposed.asBoolean) {
                throw IllegalStateException("Project was disposed while waiting for $stage")
            }
            val current = observation.get()
            if (current != lastObservation) {
                lastObservation = current
                lastProgressAt = nanoTime()
            }
            val evidence = deadlineEvidence(stage, startedAt, lastProgressAt)
            if (evidence.noProgress >= noProgressTimeout || evidence.elapsed >= maximumWait) {
                throw RuntimeProgressDeadlineExceeded(evidence)
            }
            pause(observationInterval)
        }
        future.get()
        return deadlineEvidence(stage, startedAt, lastProgressAt)
    }

    @Throws(InterruptedException::class)
    fun awaitCondition(
        stage: RuntimeProgressStage,
        completed: BooleanSupplier,
        observation: Supplier<Any>,
        disposed: BooleanSupplier,
    ): RuntimeProgressDeadlineEvidence {
        val startedAt = nanoTime()
        var lastProgressAt = startedAt
        var lastObservation: Any? = null
        while (!completed.asBoolean) {
            if (disposed.asBoolean) {
                throw IllegalStateException("Project was disposed while waiting for $stage")
            }
            val current = observation.get()
            if (current != lastObservation) {
                lastObservation = current
                lastProgressAt = nanoTime()
            }
            val evidence = deadlineEvidence(stage, startedAt, lastProgressAt)
            if (evidence.noProgress >= noProgressTimeout || evidence.elapsed >= maximumWait) {
                throw RuntimeProgressDeadlineExceeded(evidence)
            }
            pause(observationInterval)
        }
        return deadlineEvidence(stage, startedAt, lastProgressAt)
    }

    private fun deadlineEvidence(
        stage: RuntimeProgressStage,
        startedAt: Long,
        lastProgressAt: Long,
    ): RuntimeProgressDeadlineEvidence {
        val observedAt = nanoTime()
        return RuntimeProgressDeadlineEvidence(
            stage = stage,
            elapsed = Duration.ofNanos((observedAt - startedAt).coerceAtLeast(0)),
            noProgress = Duration.ofNanos((observedAt - lastProgressAt).coerceAtLeast(0)),
        )
    }

    companion object {
        @JvmStatic
        fun standard(): ProgressAwareFutureAwaiter = ProgressAwareFutureAwaiter(
            noProgressTimeout = Duration.ofMinutes(15),
            maximumWait = Duration.ofHours(1),
            observationInterval = Duration.ofMillis(100),
            nanoTime = System::nanoTime,
            pause = { duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()) },
        )
    }
}
