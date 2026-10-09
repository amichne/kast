package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearchScope
import kotlinx.serialization.Serializable

/** Delay is elapsed wall time, including native internal waits; it is not CPU or work evidence. */
@Serializable
internal data class HostedReadSearchCount(
    val search: IntellijReadSearch,
    val entered: Long,
    val firstCallbackObserved: Long,
    val returnedWithoutCallback: Long,
    val cancelledWithoutCallback: Long,
    val failedWithoutCallback: Long,
    val unfinishedWithoutCallback: Long,
    val qualification: HostedReadCallCountQualification,
    val firstCallbackDelayTotalNanos: Long,
    val firstCallbackDelayMaximumNanos: Long,
)

/** Two fixed aggregates; each active scope holds only its start time and finite lifecycle state. */
internal class HostedReadSearchAccounting(
    private val elapsed: () -> Long,
    private val ceiling: Long,
    private val enterCall: (IntellijReadCall) -> IntellijReadCallScope,
) {
    private val counts = IntellijReadSearch.entries.associateWith { Count() }
    private var lifecycle = Lifecycle.ACTIVE

    fun enter(search: IntellijReadSearch): IntellijReadSearchScope {
        val started = elapsed()
        val call = enterCall(search.call)
        val count =
            synchronized(this) {
                check(lifecycle == Lifecycle.ACTIVE) { "Native search entered after accounting finished" }
                counts.getValue(search).also {
                    it.entered = it.increment(it.entered)
                    it.unfinished = it.increment(it.unfinished)
                }
            }
        return object : IntellijReadSearchScope {
            private var callback = Callback.NOT_OBSERVED
            private var completion = Lifecycle.ACTIVE

            override fun callbackEntered(): Unit =
                synchronized(this@HostedReadSearchAccounting) {
                    check(completion == Lifecycle.ACTIVE) { "Callback entered after native search returned" }
                    if (lifecycle == Lifecycle.FINISHED || callback == Callback.OBSERVED) return
                    callback = Callback.OBSERVED
                    count.firstCallback = count.increment(count.firstCallback)
                    count.unfinished = (count.unfinished - 1).coerceAtLeast(0)
                    val delay = (elapsed() - started).coerceAtLeast(0)
                    count.delayTotal = count.addDuration(count.delayTotal, delay)
                    count.delayMaximum = maxOf(count.delayMaximum, delay)
                }

            override fun finish(outcome: IntellijReadCallOutcome) {
                call.finish(outcome)
                synchronized(this@HostedReadSearchAccounting) {
                    check(completion == Lifecycle.ACTIVE) { "Native search completed twice" }
                    completion = Lifecycle.FINISHED
                    if (lifecycle == Lifecycle.FINISHED || callback == Callback.OBSERVED) return
                    count.unfinished = (count.unfinished - 1).coerceAtLeast(0)
                    when (outcome) {
                        IntellijReadCallOutcome.RETURNED -> count.returned = count.increment(count.returned)
                        IntellijReadCallOutcome.CANCELLED -> count.cancelled = count.increment(count.cancelled)
                        IntellijReadCallOutcome.FAILED -> count.failed = count.increment(count.failed)
                    }
                }
            }
        }
    }

    @Synchronized
    fun finish(): List<HostedReadSearchCount> {
        lifecycle = Lifecycle.FINISHED
        return counts.map { (search, count) ->
            HostedReadSearchCount(
                search,
                count.entered,
                count.firstCallback,
                count.returned,
                count.cancelled,
                count.failed,
                count.unfinished,
                count.qualification,
                count.delayTotal,
                count.delayMaximum,
            )
        }
    }

    private inner class Count {
        var entered = 0L
        var firstCallback = 0L
        var returned = 0L
        var cancelled = 0L
        var failed = 0L
        var unfinished = 0L
        var delayTotal = 0L
        var delayMaximum = 0L
        var qualification = HostedReadCallCountQualification.EXACT

        fun increment(value: Long): Long =
            if (value >= ceiling) {
                qualification = HostedReadCallCountQualification.SATURATED
                ceiling
            } else value + 1

        fun addDuration(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) {
                qualification = HostedReadCallCountQualification.SATURATED
                Long.MAX_VALUE
            } else left + right
    }

    private enum class Callback {
        NOT_OBSERVED,
        OBSERVED,
    }

    private enum class Lifecycle {
        ACTIVE,
        FINISHED,
    }
}
