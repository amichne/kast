package io.github.amichne.kast.indexer.gradle.settlement

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@JvmInline
value class MonotonicInstant private constructor(
    private val nanos: Long,
) {
    /**
     * Proof transition: `(MonotonicInstant, MonotonicInstant) -> Duration`.
     *
     * Derives a non-negative elapsed duration inside one monotonic clock
     * domain. Clock regression is closed to zero here; raw nanoseconds are
     * never exposed beyond [MonotonicClock].
     */
    fun elapsedSince(earlier: MonotonicInstant): Duration =
        Duration.ofNanos((nanos - earlier.nanos).coerceAtLeast(0))

    companion object {
        internal fun capture(rawClock: () -> Long): MonotonicInstant = MonotonicInstant(rawClock())
    }
}

fun interface MonotonicClock {
    fun now(): MonotonicInstant

    companion object {
        /**
         * Proof transition: `() -> Long -> MonotonicClock`.
         *
         * Confines raw monotonic nanoseconds to a clock capability whose callers
         * can obtain only [MonotonicInstant] values.
         */
        fun fromRaw(rawClock: () -> Long): MonotonicClock = MonotonicClock {
            MonotonicInstant.capture(rawClock)
        }

        @JvmStatic
        fun system(): MonotonicClock = fromRaw(System::nanoTime)
    }
}

@ConsistentCopyVisibility
data class RuntimeProgressWaitPolicy private constructor(
    val noProgressTimeout: Duration,
    val maximumWait: Duration,
    val observationInterval: Duration,
) {
    companion object {
        /**
         * Proof transition:
         * `(Duration, Duration, Duration) -> RuntimeProgressWaitPolicy`.
         *
         * Establishes positive observation and no-progress intervals plus a
         * maximum wait that cannot be shorter than the no-progress deadline.
         * Invalid values are programming defects at policy construction, not
         * runtime wait outcomes.
         */
        fun derive(
            noProgressTimeout: Duration,
            maximumWait: Duration,
            observationInterval: Duration,
        ): RuntimeProgressWaitPolicy {
            require(noProgressTimeout.isPositive()) { "No-progress timeout must be positive" }
            require(maximumWait >= noProgressTimeout) {
                "Maximum wait must be at least the no-progress timeout"
            }
            require(observationInterval.isPositive()) { "Observation interval must be positive" }
            return RuntimeProgressWaitPolicy(noProgressTimeout, maximumWait, observationInterval)
        }

        @JvmStatic
        fun standard(): RuntimeProgressWaitPolicy = derive(
            noProgressTimeout = Duration.ofMinutes(15),
            maximumWait = Duration.ofHours(1),
            observationInterval = Duration.ofMillis(100),
        )
    }
}

@ConsistentCopyVisibility
data class RuntimeProgressObservation private constructor(
    private val token: Any,
) {
    companion object {
        /**
         * Proof transition: `Any -> RuntimeProgressObservation`.
         *
         * Marks an opaque external observation as a comparable progress token;
         * the token is never interpreted or extracted by the wait loop.
         */
        @JvmStatic
        fun capture(token: Any): RuntimeProgressObservation = RuntimeProgressObservation(token)
    }
}

fun interface RuntimeProgressProbe {
    fun observe(): RuntimeProgressObservation
}

enum class RuntimeWaitLifecycle {
    Active,
    Disposed,
}

fun interface RuntimeWaitLifecycleProbe {
    fun observe(): RuntimeWaitLifecycle
}

enum class RuntimeWaitCompletion {
    Pending,
    Completed,
}

fun interface RuntimeWaitCompletionProbe {
    fun observe(): RuntimeWaitCompletion
}

@ConsistentCopyVisibility
data class RuntimeProgressDeadlineEvidence private constructor(
    val stage: RuntimeProgressStage,
    val elapsed: Duration,
    val noProgress: Duration,
) {
    companion object {
        /**
         * Proof transition:
         * `(RuntimeProgressStage, MonotonicInstant, MonotonicInstant, MonotonicInstant)`
         * `-> RuntimeProgressDeadlineEvidence`.
         *
         * Derives non-negative elapsed and no-progress durations from one
         * monotonic clock domain. The output guarantees `noProgress <= elapsed`.
         */
        fun derive(
            stage: RuntimeProgressStage,
            startedAt: MonotonicInstant,
            lastProgressAt: MonotonicInstant,
            observedAt: MonotonicInstant,
        ): RuntimeProgressDeadlineEvidence {
            val elapsed = observedAt.elapsedSince(startedAt)
            val noProgress = observedAt.elapsedSince(lastProgressAt).coerceAtMost(elapsed)
            return RuntimeProgressDeadlineEvidence(stage, elapsed, noProgress)
        }

        private fun Duration.coerceAtMost(maximum: Duration): Duration = if (this > maximum) maximum else this
    }
}

sealed interface RuntimeProgressAwaitFailure {
    val evidence: RuntimeProgressDeadlineEvidence

    data class DeadlineExceeded(
        override val evidence: RuntimeProgressDeadlineEvidence,
    ) : RuntimeProgressAwaitFailure

    data class ProjectDisposed(
        override val evidence: RuntimeProgressDeadlineEvidence,
    ) : RuntimeProgressAwaitFailure

    data class Interrupted(
        override val evidence: RuntimeProgressDeadlineEvidence,
    ) : RuntimeProgressAwaitFailure

    data class FutureFailed(
        override val evidence: RuntimeProgressDeadlineEvidence,
        val cause: Throwable,
    ) : RuntimeProgressAwaitFailure

    data class FutureCancelled(
        override val evidence: RuntimeProgressDeadlineEvidence,
    ) : RuntimeProgressAwaitFailure
}

sealed interface RuntimeProgressAwaitOutcome {
    data class Completed(
        val evidence: RuntimeProgressDeadlineEvidence,
    ) : RuntimeProgressAwaitOutcome

    data class Rejected(
        val failure: RuntimeProgressAwaitFailure,
    ) : RuntimeProgressAwaitOutcome
}

/**
 * Outer adapter transition:
 * `RuntimeProgressAwaitFailure -> RuntimeProgressAwaitException`.
 *
 * Converts an already-typed failure only for Java and IntelliJ APIs that
 * require exception protocols. Kotlin wait logic returns
 * [RuntimeProgressAwaitOutcome] and never catches this adapter to recover
 * domain state.
 */
class RuntimeProgressAwaitException(
    val failure: RuntimeProgressAwaitFailure,
) : IllegalStateException(render(failure), (failure as? RuntimeProgressAwaitFailure.FutureFailed)?.cause) {
    private companion object {
        fun render(failure: RuntimeProgressAwaitFailure): String = when (failure) {
            is RuntimeProgressAwaitFailure.DeadlineExceeded -> "Runtime progress deadline exceeded"
            is RuntimeProgressAwaitFailure.ProjectDisposed -> "Project was disposed while waiting for runtime progress"
            is RuntimeProgressAwaitFailure.Interrupted -> "Runtime progress wait was interrupted"
            is RuntimeProgressAwaitFailure.FutureFailed -> "Runtime progress future failed"
            is RuntimeProgressAwaitFailure.FutureCancelled -> "Runtime progress future was cancelled"
        } + ": stage=${failure.evidence.stage}, elapsed=${failure.evidence.elapsed}, " +
            "noProgress=${failure.evidence.noProgress}"
    }
}

class ProgressAwareFutureAwaiter internal constructor(
    private val policy: RuntimeProgressWaitPolicy,
    private val clock: MonotonicClock,
    private val pause: (Duration) -> Unit,
) {
    /**
     * Proof transition:
     * `(CompletableFuture<Void>, RuntimeProgressProbe, RuntimeWaitLifecycleProbe)`
     * `-> RuntimeProgressAwaitOutcome`.
     *
     * Completion and every expected wait failure are returned as finite data.
     * The future, observation, lifecycle, thread, and clock are effect boundaries;
     * no Boolean, null sentinel, or exception is used as the result protocol.
     */
    fun await(
        stage: RuntimeProgressStage,
        future: CompletableFuture<Void>,
        observation: RuntimeProgressProbe,
        lifecycle: RuntimeWaitLifecycleProbe,
    ): RuntimeProgressAwaitOutcome {
        val startedAt = clock.now()
        var lastProgressAt = startedAt
        var previous: PreviousProgressObservation = PreviousProgressObservation.Unobserved
        while (!future.isDone) {
            val evidence = evidence(stage, startedAt, lastProgressAt)
            if (lifecycle.observe() == RuntimeWaitLifecycle.Disposed) {
                return rejected(RuntimeProgressAwaitFailure.ProjectDisposed(evidence))
            }
            val current = observation.observe()
            if (previous != PreviousProgressObservation.Observed(current)) {
                previous = PreviousProgressObservation.Observed(current)
                lastProgressAt = clock.now()
            }
            val currentEvidence = evidence(stage, startedAt, lastProgressAt)
            when (val deadline = deadlineStatus(currentEvidence)) {
                RuntimeProgressDeadlineStatus.WithinDeadline -> Unit
                is RuntimeProgressDeadlineStatus.Exceeded -> return rejected(deadline.failure)
            }
            try {
                pause(policy.observationInterval)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return rejected(RuntimeProgressAwaitFailure.Interrupted(evidence(stage, startedAt, lastProgressAt)))
            }
        }
        val completedEvidence = evidence(stage, startedAt, lastProgressAt)
        return try {
            future.get()
            RuntimeProgressAwaitOutcome.Completed(completedEvidence)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            rejected(RuntimeProgressAwaitFailure.Interrupted(completedEvidence))
        } catch (failure: ExecutionException) {
            rejected(RuntimeProgressAwaitFailure.FutureFailed(completedEvidence, failure.cause ?: failure))
        } catch (_: CancellationException) {
            rejected(RuntimeProgressAwaitFailure.FutureCancelled(completedEvidence))
        }
    }

    /**
     * Proof transition:
     * `(RuntimeWaitCompletionProbe, RuntimeProgressProbe, RuntimeWaitLifecycleProbe)`
     * `-> RuntimeProgressAwaitOutcome`.
     *
     * The closed completion and lifecycle observations are retained through a
     * finite outcome; raw Boolean suppliers and thrown expected failures do not
     * cross this API.
     */
    fun awaitCondition(
        stage: RuntimeProgressStage,
        completion: RuntimeWaitCompletionProbe,
        observation: RuntimeProgressProbe,
        lifecycle: RuntimeWaitLifecycleProbe,
    ): RuntimeProgressAwaitOutcome {
        val startedAt = clock.now()
        var lastProgressAt = startedAt
        var previous: PreviousProgressObservation = PreviousProgressObservation.Unobserved
        while (completion.observe() == RuntimeWaitCompletion.Pending) {
            val evidence = evidence(stage, startedAt, lastProgressAt)
            if (lifecycle.observe() == RuntimeWaitLifecycle.Disposed) {
                return rejected(RuntimeProgressAwaitFailure.ProjectDisposed(evidence))
            }
            val current = observation.observe()
            if (previous != PreviousProgressObservation.Observed(current)) {
                previous = PreviousProgressObservation.Observed(current)
                lastProgressAt = clock.now()
            }
            val currentEvidence = evidence(stage, startedAt, lastProgressAt)
            when (val deadline = deadlineStatus(currentEvidence)) {
                RuntimeProgressDeadlineStatus.WithinDeadline -> Unit
                is RuntimeProgressDeadlineStatus.Exceeded -> return rejected(deadline.failure)
            }
            try {
                pause(policy.observationInterval)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return rejected(RuntimeProgressAwaitFailure.Interrupted(evidence(stage, startedAt, lastProgressAt)))
            }
        }
        return RuntimeProgressAwaitOutcome.Completed(evidence(stage, startedAt, lastProgressAt))
    }

    private fun evidence(
        stage: RuntimeProgressStage,
        startedAt: MonotonicInstant,
        lastProgressAt: MonotonicInstant,
    ): RuntimeProgressDeadlineEvidence = RuntimeProgressDeadlineEvidence.derive(
        stage = stage,
        startedAt = startedAt,
        lastProgressAt = lastProgressAt,
        observedAt = clock.now(),
    )

    /**
     * Proof transition:
     * `RuntimeProgressDeadlineEvidence -> RuntimeProgressDeadlineStatus`.
     *
     * Classifies already-bounded timing evidence into a closed deadline state;
     * absence is [RuntimeProgressDeadlineStatus.WithinDeadline], never `null`.
     */
    private fun deadlineStatus(
        evidence: RuntimeProgressDeadlineEvidence,
    ): RuntimeProgressDeadlineStatus = if (
        evidence.noProgress >= policy.noProgressTimeout || evidence.elapsed >= policy.maximumWait
    ) {
        RuntimeProgressDeadlineStatus.Exceeded(RuntimeProgressAwaitFailure.DeadlineExceeded(evidence))
    } else {
        RuntimeProgressDeadlineStatus.WithinDeadline
    }

    private fun rejected(failure: RuntimeProgressAwaitFailure): RuntimeProgressAwaitOutcome.Rejected =
        RuntimeProgressAwaitOutcome.Rejected(failure)

    companion object {
        @JvmStatic
        fun standard(): ProgressAwareFutureAwaiter = ProgressAwareFutureAwaiter(
            policy = RuntimeProgressWaitPolicy.standard(),
            clock = MonotonicClock.system(),
            pause = { duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()) },
        )
    }
}

private sealed interface RuntimeProgressDeadlineStatus {
    data object WithinDeadline : RuntimeProgressDeadlineStatus

    data class Exceeded(
        val failure: RuntimeProgressAwaitFailure.DeadlineExceeded,
    ) : RuntimeProgressDeadlineStatus
}

private sealed interface PreviousProgressObservation {
    data object Unobserved : PreviousProgressObservation

    data class Observed(
        val observation: RuntimeProgressObservation,
    ) : PreviousProgressObservation
}
