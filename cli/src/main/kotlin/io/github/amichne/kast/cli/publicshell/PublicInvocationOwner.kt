package io.github.amichne.kast.cli.publicshell

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/** The first accepted terminal event owns status. A prepared document is not successful delivery. */
internal class PublicInvocationOwner {
    private sealed interface State {
        data object Active : State

        data object Writing : State

        data class Terminal(val exit: PublicShellExit) : State
    }

    private val state = AtomicReference<State>(State.Active)
    val active: Boolean
        get() = state.get() !is State.Terminal

    val exit: PublicShellExit
        get() =
            when (val value = state.get()) {
                State.Active,
                State.Writing -> error("Invocation has no terminal event")
                is State.Terminal -> value.exit
            }

    fun cancel(exit: PublicShellExit): Boolean {
        require(exit == PublicShellExit.INTERRUPTED || exit == PublicShellExit.TERMINATED)
        return settle(exit)
    }

    fun flushed(exit: PublicShellExit): Boolean = settle(exit)

    fun outputFailed(): Boolean = settle(PublicShellExit.IO)

    fun failedIfActive(): Boolean = settle(PublicShellExit.DEFECT)

    private fun settle(exit: PublicShellExit): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.Active,
                State.Writing -> if (state.compareAndSet(current, State.Terminal(exit))) return true
                is State.Terminal -> return false
            }
        }
    }

    fun publishDefect(prepare: () -> PublicPreparedOutput, output: OutputStream) {
        while (true) {
            when (val current = state.get()) {
                State.Active ->
                    if (state.compareAndSet(current, State.Terminal(PublicShellExit.DEFECT))) {
                        publishDiagnostic(prepare, output)
                        return
                    }
                State.Writing -> if (state.compareAndSet(current, State.Terminal(PublicShellExit.DEFECT))) return
                is State.Terminal -> return
            }
        }
    }

    private fun publishDiagnostic(prepare: () -> PublicPreparedOutput, output: OutputStream) {
        // The defect is already terminal. Diagnostic delivery cannot replace its status.
        try {
            output.write((prepare().text + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {
            // Best-effort output after a terminal defect has no further transition.
        }
    }

    /** One invocation worker is the sole writer. Signal callbacks only select status and cancel that worker. */
    fun publish(prepared: PublicPreparedOutput, output: OutputStream) {
        if (state.compareAndSet(State.Active, State.Writing)) {
            try {
                val bytes = (prepared.text + "\n").toByteArray(Charsets.UTF_8)
                var offset = 0
                while (offset < bytes.size && active) {
                    val size = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, size)
                    offset += size
                }
                if (active) {
                    output.flush()
                    flushed(prepared.exit)
                }
            } catch (_: IOException) {
                outputFailed()
            } catch (_: RuntimeException) {
                failedIfActive()
            }
        }
    }
}
