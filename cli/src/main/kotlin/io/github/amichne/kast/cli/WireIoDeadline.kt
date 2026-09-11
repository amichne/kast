package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import java.nio.channels.SocketChannel
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class WireRequestStage {
    CONNECTION,
    PEER_QUALIFICATION,
    EXCHANGE,
}

enum class WireRequestOutcome {
    COMPLETED,
    REJECTED,
    TIMED_OUT,
}

data class WireRequestActivity(val stage: WireRequestStage, val outcome: WireRequestOutcome)

fun interface WireActivitySink {
    fun publish(activity: WireRequestActivity)

    object Disabled : WireActivitySink {
        override fun publish(activity: WireRequestActivity) = Unit
    }
}

internal class JsonLineWireActivitySink(private val output: java.io.PrintStream) : WireActivitySink {
    @Synchronized
    override fun publish(activity: WireRequestActivity) {
        output.println(
            "{\"component\":\"kast-wire\",\"stage\":\"${activity.stage.name.lowercase()}\",\"outcome\":\"${activity.outcome.name.lowercase()}\"}"
        )
    }
}

/** Elapsed expiry closes the exact blocking channel, independently of cooperative cancellation. */
internal class WireIoDeadline(channel: SocketChannel, budget: ElapsedTimeLimitMillis) {
    private enum class State {
        RUNNING,
        COMPLETED,
        EXPIRED,
    }

    private val state = AtomicReference(State.RUNNING)
    private val started = System.nanoTime()
    private val allowanceNanos = TimeUnit.MILLISECONDS.toNanos(budget.value)
    private val ownedChannel = channel
    private val expiration =
        timer.schedule(
            {
                if (state.compareAndSet(State.RUNNING, State.EXPIRED)) closeOwnedChannel()
            },
            budget.value,
            TimeUnit.MILLISECONDS,
        )

    fun finish(): WireRequestOutcome {
        if (System.nanoTime() - started >= allowanceNanos) state.compareAndSet(State.RUNNING, State.EXPIRED)
        expiration.cancel(false)
        state.compareAndSet(State.RUNNING, State.COMPLETED)
        return if (state.get() == State.COMPLETED) WireRequestOutcome.COMPLETED
        else {
            // Join closure logically: never return timeout while the owned blocking I/O remains open.
            closeOwnedChannel()
            WireRequestOutcome.TIMED_OUT
        }
    }

    private fun closeOwnedChannel() {
        try {
            ownedChannel.close()
        } catch (_: java.io.IOException) {
            /* Channel is invalidated by close. */
        }
    }

    companion object {
        private val timer =
            ScheduledThreadPoolExecutor(1) { operation ->
                    Thread(operation, "kast-wire-deadlines").apply { isDaemon = true }
                }
                .apply { removeOnCancelPolicy = true }
    }
}
