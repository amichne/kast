package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionMode
import io.github.amichne.kast.workspace.intellij.read.IntellijReadActionScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import kotlinx.serialization.Serializable

@Serializable
internal data class HostedReadActionOutcomes(
    val entered: Long,
    val returned: Long,
    val cancelled: Long,
    val failed: Long,
    val unfinished: Long,
    val qualification: HostedReadCallCountQualification,
)

/** Includes coroutine scheduling and acquisition; does not isolate the platform mutex or hidden smart-mode waits. */
@Serializable
internal enum class HostedReadActionWait {
    INITIAL_ADMISSION,
    RETRY_ADMISSION,
    RETURN_DISPATCH,
    AFTER_COMPLETION_ADMISSION,
    AFTER_COMPLETION_DRAINAGE,
}

@Serializable
internal data class HostedReadActionWaitCount(
    val wait: HostedReadActionWait,
    val samples: Long,
    val durationNanos: Long,
    val maximumNanos: Long,
    val qualification: HostedReadCallCountQualification,
)

@Serializable
internal data class HostedReadActionCount(
    val kind: IntellijReadActionKind,
    val mode: IntellijReadActionMode,
    val submissions: HostedReadActionOutcomes,
    val attempts: HostedReadActionOutcomes,
    val cancelledBeforeAcquisition: Long,
    val failedBeforeAcquisition: Long,
    val attemptsAfterCompletion: Long,
    val qualification: HostedReadCallCountQualification,
    val firstSubmission: HostedReadCallEntry,
    val waits: List<HostedReadActionWaitCount>,
)

@Serializable
internal data class HostedReadActionStarted(
    val readId: String,
    val kind: IntellijReadActionKind,
    val mode: IntellijReadActionMode,
    val submittedNanos: Long,
)

/** Fixed aggregates. Active scopes carry only finite state and timestamps, never native objects or a parent stack. */
internal class HostedReadActionAccounting(
    private val elapsed: () -> Long,
    private val ceiling: Long,
    private val publishEntry: (IntellijReadActionKind, IntellijReadActionMode, Long) -> Unit = { _, _, _ -> },
) {
    private val counts =
        IntellijReadActionKind.entries
            .flatMap { kind ->
                IntellijReadActionMode.entries.map { mode -> (kind to mode) to Count() }
            }
            .toMap()
    private var lifecycle = Lifecycle.ACTIVE

    @Synchronized
    fun submit(kind: IntellijReadActionKind, mode: IntellijReadActionMode): IntellijReadActionScope {
        check(lifecycle == Lifecycle.ACTIVE) { "Read action submitted after accounting finished" }
        val started = elapsed()
        val count = counts.getValue(kind to mode)
        count.submissions.enter()
        if (count.firstSubmission == HostedReadCallEntry.NotEntered) {
            count.firstSubmission = HostedReadCallEntry.Entered(started)
            publishEntry(kind, mode, started)
        }
        return Submission(started, count)
    }

    private inner class Submission(started: Long, private val count: Count) : IntellijReadActionScope {
        private var phase: Phase = Phase.Initial(started)
        private var completion: Completion = Completion.Pending

        override fun enterAttempt(): IntellijReadCallScope =
            synchronized(this@HostedReadActionAccounting) {
                check(lifecycle == Lifecycle.ACTIVE) { "Read action acquired after accounting finished" }
                val now = elapsed()
                val previous = phase
                check(previous !is Phase.Running) { "Native read attempts must not overlap" }
                if (completion is Completion.Completed) {
                    count.afterCompletion = count.increment(count.afterCompletion)
                    count.wait(HostedReadActionWait.AFTER_COMPLETION_ADMISSION, now - previous.since)
                } else
                    when (previous) {
                        is Phase.Initial -> count.wait(HostedReadActionWait.INITIAL_ADMISSION, now - previous.since)
                        is Phase.Between -> count.wait(HostedReadActionWait.RETRY_ADMISSION, now - previous.since)
                        is Phase.Running -> error("Overlapping attempt passed admission")
                    }
                phase = Phase.Running(now)
                count.attempts.enter()
                object : IntellijReadCallScope {
                    private var state = Lifecycle.ACTIVE

                    override fun finish(outcome: IntellijReadCallOutcome): Unit =
                        synchronized(this@HostedReadActionAccounting) {
                            check(state == Lifecycle.ACTIVE) { "Native read attempt completed twice" }
                            state = Lifecycle.FINISHED
                            val ended = elapsed()
                            phase = Phase.Between(ended)
                            if (lifecycle == Lifecycle.FINISHED) return
                            count.attempts.exit(outcome)
                            (completion as? Completion.Completed)?.let {
                                count.wait(HostedReadActionWait.AFTER_COMPLETION_DRAINAGE, ended - it.nanos)
                            }
                        }
                }
            }

        override fun finish(outcome: IntellijReadCallOutcome): Unit =
            synchronized(this@HostedReadActionAccounting) {
                check(completion == Completion.Pending) { "Read action submission completed twice" }
                val ended = elapsed()
                completion = Completion.Completed(ended)
                if (lifecycle == Lifecycle.FINISHED) return
                count.submissions.exit(outcome)
                when (val previous = phase) {
                    is Phase.Initial -> {
                        count.wait(HostedReadActionWait.INITIAL_ADMISSION, ended - previous.since)
                        phase = Phase.Initial(ended)
                        when (outcome) {
                            IntellijReadCallOutcome.CANCELLED ->
                                count.cancelledBefore = count.increment(count.cancelledBefore)
                            IntellijReadCallOutcome.FAILED -> count.failedBefore = count.increment(count.failedBefore)
                            IntellijReadCallOutcome.RETURNED -> Unit
                        }
                    }
                    is Phase.Between -> {
                        count.wait(HostedReadActionWait.RETURN_DISPATCH, ended - previous.since)
                        phase = Phase.Between(ended)
                    }
                    is Phase.Running -> Unit // Native drainage is a separate observed fact.
                }
            }
    }

    @Synchronized
    fun finish(): List<HostedReadActionCount> {
        lifecycle = Lifecycle.FINISHED
        return counts.map { (key, count) ->
            val submissions = count.submissions.snapshot()
            val attempts = count.attempts.snapshot()
            val waits = count.waits.map { (wait, timing) -> timing.snapshot(wait) }
            val outcomesSaturated =
                submissions.qualification == HostedReadCallCountQualification.SATURATED ||
                    attempts.qualification == HostedReadCallCountQualification.SATURATED
            val qualification =
                if (
                    count.qualification == HostedReadCallCountQualification.SATURATED ||
                        outcomesSaturated ||
                        waits.any { it.qualification == HostedReadCallCountQualification.SATURATED }
                )
                    HostedReadCallCountQualification.SATURATED
                else HostedReadCallCountQualification.EXACT
            HostedReadActionCount(
                key.first,
                key.second,
                submissions,
                attempts,
                count.cancelledBefore,
                count.failedBefore,
                count.afterCompletion,
                qualification,
                count.firstSubmission,
                waits,
            )
        }
    }

    private inner class Count {
        val submissions = Outcomes()
        val attempts = Outcomes()
        val waits = HostedReadActionWait.entries.associateWith { Timing() }
        var cancelledBefore = 0L
        var failedBefore = 0L
        var afterCompletion = 0L
        var qualification = HostedReadCallCountQualification.EXACT
        var firstSubmission: HostedReadCallEntry = HostedReadCallEntry.NotEntered

        fun increment(value: Long): Long =
            if (value >= ceiling) {
                qualification = HostedReadCallCountQualification.SATURATED
                ceiling
            } else value + 1

        fun wait(wait: HostedReadActionWait, nanos: Long) = waits.getValue(wait).add(nanos.coerceAtLeast(0))
    }

    private inner class Outcomes {
        private var entered = 0L
        private var returned = 0L
        private var cancelled = 0L
        private var failed = 0L
        private var unfinished = 0L
        private var qualification = HostedReadCallCountQualification.EXACT

        fun enter() {
            entered = increment(entered)
            unfinished = increment(unfinished)
        }

        fun exit(outcome: IntellijReadCallOutcome) {
            unfinished = (unfinished - 1).coerceAtLeast(0)
            when (outcome) {
                IntellijReadCallOutcome.RETURNED -> returned = increment(returned)
                IntellijReadCallOutcome.CANCELLED -> cancelled = increment(cancelled)
                IntellijReadCallOutcome.FAILED -> failed = increment(failed)
            }
        }

        private fun increment(value: Long): Long =
            if (value >= ceiling) {
                qualification = HostedReadCallCountQualification.SATURATED
                ceiling
            } else value + 1

        fun snapshot() = HostedReadActionOutcomes(entered, returned, cancelled, failed, unfinished, qualification)
    }

    private inner class Timing {
        private var samples = 0L
        private var total = 0L
        private var maximum = 0L
        private var qualification = HostedReadCallCountQualification.EXACT

        fun add(nanos: Long) {
            if (samples >= ceiling) qualification = HostedReadCallCountQualification.SATURATED else samples++
            if (nanos > Long.MAX_VALUE - total) {
                total = Long.MAX_VALUE
                qualification = HostedReadCallCountQualification.SATURATED
            } else total += nanos
            maximum = maxOf(maximum, nanos)
        }

        fun snapshot(wait: HostedReadActionWait) =
            HostedReadActionWaitCount(wait, samples, total, maximum, qualification)
    }

    private sealed interface Phase {
        val since: Long

        data class Initial(override val since: Long) : Phase

        data class Running(override val since: Long) : Phase

        data class Between(override val since: Long) : Phase
    }

    private sealed interface Completion {
        data object Pending : Completion

        data class Completed(val nanos: Long) : Completion
    }

    private enum class Lifecycle {
        ACTIVE,
        FINISHED,
    }
}
