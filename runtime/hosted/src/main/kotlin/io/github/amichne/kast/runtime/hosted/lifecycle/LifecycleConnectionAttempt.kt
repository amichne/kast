package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCommand
import io.github.amichne.kast.protocol.contract.exchangeCommand
import kotlinx.serialization.Serializable

@Serializable
internal enum class LifecycleConnectionStage {
    READ_FRAME,
    DISPATCH,
    WRITE_FRAME,
}

@Serializable
internal enum class LifecycleConnectionCause {
    DEADLINE_EXCEEDED,
    INTERRUPTED,
    IO_FAILURE,
}

/** One bounded failure record, never request contents, roots, source or approval payloads. */
@Serializable
internal data class LifecycleConnectionFailure(
    val command: IdeLifecycleExchangeCommand?,
    val stage: LifecycleConnectionStage,
    val cause: LifecycleConnectionCause,
    val elapsedMillis: Long,
    val limitMillis: Long,
)

internal class LifecycleConnectionAttempt(private val nanoTime: () -> Long = System::nanoTime) {
    private val started = nanoTime()
    private var stage = LifecycleConnectionStage.READ_FRAME
    private var command: IdeLifecycleExchangeCommand? = null

    fun dispatch(received: IdeLifecycleCommand) {
        command = received.exchangeCommand()
        enter(LifecycleConnectionStage.DISPATCH)
    }

    fun enter(next: LifecycleConnectionStage) {
        stage = next
    }

    fun failure(cause: LifecycleConnectionCause) =
        LifecycleConnectionFailure(
            command,
            stage,
            cause,
            (nanoTime() - started).coerceAtLeast(0) / NANOS_PER_MILLI,
            LIMIT_MILLIS,
        )

    companion object {
        const val LIMIT_MILLIS = 5_000L
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
