package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface HostedReadCallParent {
    @Serializable @SerialName("root") data object Root : HostedReadCallParent

    @Serializable @SerialName("call") data class Call(val call: IntellijReadCall) : HostedReadCallParent
}

@Serializable
internal sealed interface HostedReadCallEntry {
    @Serializable @SerialName("not-entered") data object NotEntered : HostedReadCallEntry

    @Serializable @SerialName("entered") data class Entered(val nanos: Long) : HostedReadCallEntry
}

@Serializable
internal enum class HostedReadCallCountQualification {
    EXACT,
    SATURATED,
}

/** Per-call durations are inclusive, can overlap, and are not a sum of CPU costs. */
@Serializable
internal data class HostedReadCallCount(
    val call: IntellijReadCall,
    val parent: HostedReadCallParent,
    val entered: Long,
    val returned: Long,
    val cancelled: Long,
    val failed: Long,
    val unfinished: Long,
    val qualification: HostedReadCallCountQualification,
    val firstEntry: HostedReadCallEntry,
    val durationNanos: Long,
)

@Serializable
internal data class HostedReadCallStarted(
    val readId: String,
    val call: IntellijReadCall,
    val parent: HostedReadCallParent,
    val enteredNanos: Long,
)

/** Bounded by the closed call vocabulary squared; no per-invocation history is retained. */
internal class HostedReadCallAccounting(
    private val elapsed: () -> Long,
    private val ceiling: Long,
    private val publishEntry: (IntellijReadCall, HostedReadCallParent, Long) -> Unit = { _, _, _ -> },
) {
    private val parents = ThreadLocal<ArrayDeque<IntellijReadCall>>()
    private val counts =
        linkedMapOf<Pair<IntellijReadCall, HostedReadCallParent>, Count>().apply {
            IntellijReadCall.entries.forEach { put(it to HostedReadCallParent.Root, Count()) }
        }
    private var state: State = State.Active

    fun enter(call: IntellijReadCall): IntellijReadCallScope {
        val stack = parents.get() ?: ArrayDeque<IntellijReadCall>().also(parents::set)
        val parent = stack.lastOrNull()?.let(HostedReadCallParent::Call) ?: HostedReadCallParent.Root
        val started = elapsed()
        val count =
            synchronized(this) {
                check(state == State.Active) { "Native call entered after operation accounting finished" }
                counts.getOrPut(call to parent, ::Count).also {
                    it.entered = it.increment(it.entered)
                    it.unfinished = it.increment(it.unfinished)
                    if (it.firstEntry == HostedReadCallEntry.NotEntered) {
                        it.firstEntry = HostedReadCallEntry.Entered(started)
                        publishEntry(call, parent, started)
                    }
                }
            }
        stack.addLast(call)
        return object : IntellijReadCallScope {
            private var completion: Completion = Completion.Pending

            override fun finish(outcome: IntellijReadCallOutcome) {
                check(completion == Completion.Pending) { "Native call completed twice" }
                completion = Completion.Finished
                check(stack.removeLast() == call) { "Native call parentage must close in effect order" }
                if (stack.isEmpty()) parents.remove()
                synchronized(this@HostedReadCallAccounting) {
                    if (state == State.Finished) return
                    when (outcome) {
                        IntellijReadCallOutcome.RETURNED -> count.returned = count.increment(count.returned)
                        IntellijReadCallOutcome.CANCELLED -> count.cancelled = count.increment(count.cancelled)
                        IntellijReadCallOutcome.FAILED -> count.failed = count.increment(count.failed)
                    }
                    count.unfinished = (count.unfinished - 1).coerceAtLeast(0)
                    count.duration = saturatedAdd(count.duration, (elapsed() - started).coerceAtLeast(0))
                }
            }
        }
    }

    @Synchronized
    fun finish(): List<HostedReadCallCount> {
        state = State.Finished
        return counts.map { (key, count) ->
            HostedReadCallCount(
                key.first,
                key.second,
                count.entered,
                count.returned,
                count.cancelled,
                count.failed,
                count.unfinished,
                count.qualification,
                count.firstEntry,
                count.duration,
            )
        }
    }

    private inner class Count {
        var entered = 0L
        var returned = 0L
        var cancelled = 0L
        var failed = 0L
        var unfinished = 0L
        var qualification = HostedReadCallCountQualification.EXACT
        var firstEntry: HostedReadCallEntry = HostedReadCallEntry.NotEntered
        var duration = 0L

        fun increment(value: Long): Long =
            if (value >= ceiling) {
                qualification = HostedReadCallCountQualification.SATURATED
                ceiling
            } else value + 1
    }

    private enum class State {
        Active,
        Finished,
    }

    private enum class Completion {
        Pending,
        Finished,
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
