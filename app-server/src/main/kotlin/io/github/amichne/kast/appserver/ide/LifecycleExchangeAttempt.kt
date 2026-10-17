package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.protocol.contract.IdeLifecycleCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeStage
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.exchangeCommand
import java.util.concurrent.atomic.AtomicReference

/** First terminal observation wins; a late timer cannot rewrite an earlier I/O failure or success. */
internal class LifecycleExchangeAttempt(command: IdeLifecycleCommand, private val nanoTime: () -> Long) {
    private val started = nanoTime()
    private val operation = command.exchangeCommand()
    private val stage = AtomicReference(IdeLifecycleExchangeStage.PATH_ADMISSION)
    private val terminal = AtomicReference<Terminal>(Terminal.Active)

    fun enter(next: IdeLifecycleExchangeStage) {
        stage.set(next)
    }

    fun expire(close: () -> Unit) {
        if (terminal.compareAndSet(Terminal.Active, failure(IdeLifecycleExchangeCause.DEADLINE_EXCEEDED))) close()
    }

    fun reject(cause: IdeLifecycleExchangeCause): IdeLifecycleResult.Blocked {
        val candidate = failure(cause)
        return when (val selected = terminal.updateAndGet { if (it == Terminal.Active) candidate else it }) {
            is Terminal.Failed -> selected.document()
            else -> error("A completed lifecycle exchange cannot acquire a failure")
        }
    }

    fun complete(result: IdeLifecycleResult): IdeLifecycleResult {
        if (terminal.compareAndSet(Terminal.Active, Terminal.Complete)) return result
        return when (val selected = terminal.get()) {
            is Terminal.Failed -> selected.document()
            else -> error("Lifecycle exchange completed more than once")
        }
    }

    private fun failure(cause: IdeLifecycleExchangeCause) =
        Terminal.Failed(
            IdeLifecycleExchangeFailure(
                operation,
                stage.get(),
                cause,
                (nanoTime() - started).coerceAtLeast(0) / NANOS_PER_MILLI,
                LifecycleExchangeTimer.LIMIT_MILLIS,
            )
        )

    private sealed interface Terminal {
        data object Active : Terminal

        data object Complete : Terminal

        data class Failed(val observation: IdeLifecycleExchangeFailure) : Terminal
    }

    private fun Terminal.Failed.document(): IdeLifecycleResult.Blocked =
        IdeLifecycleResult.Blocked(
            when (observation.cause) {
                IdeLifecycleExchangeCause.DEADLINE_EXCEEDED -> IdeLifecycleFailure.EXCHANGE_DEADLINE_EXCEEDED
                IdeLifecycleExchangeCause.INVALID_FRAME,
                IdeLifecycleExchangeCause.INVALID_RESPONSE -> IdeLifecycleFailure.INVALID_REQUEST
                else -> IdeLifecycleFailure.HOST_UNAVAILABLE
            },
            exchange = observation,
        )
}

private const val NANOS_PER_MILLI = 1_000_000L
