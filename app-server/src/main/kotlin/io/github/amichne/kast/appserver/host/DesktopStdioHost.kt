package io.github.amichne.kast.appserver.host

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.CodexIntegrationFailure
import io.github.amichne.kast.appserver.CodexIntegrationRun
import io.github.amichne.kast.appserver.CodexIntegrationShutdownHooks
import io.github.amichne.kast.appserver.JvmCodexIntegrationShutdownHooks
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnector
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** JSONL stdio façade used when Codex Desktop starts `kast-codex` in the App Server role. */
internal class DesktopStdioHost(
    private val input: InputStream,
    private val output: OutputStream,
    private val connector: BrokerUpstreamConnector,
    private val maximumMessageBytes: Int,
    private val shutdownHooks: CodexIntegrationShutdownHooks =
        JvmCodexIntegrationShutdownHooks,
    private val shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
) : CodexIntegrationHost {
    override suspend fun run(
        closeIntegration: suspend () -> Unit,
    ): CodexIntegrationRun {
        if (maximumMessageBytes <= 0) {
            return rejectAfterClosing(
                CodexIntegrationFailure.STDIO_REJECTED,
                closeIntegration,
            )
        }
        val connection = when (val admission = connector.connect()) {
            is BrokerUpstreamConnectionAdmission.Connected -> admission.connection
            BrokerUpstreamConnectionAdmission.Rejected -> {
                return rejectAfterClosing(
                    CodexIntegrationFailure.HOST_TRANSPORT_REJECTED,
                    closeIntegration,
                )
            }
        }
        val lifetime = OwnedDesktopStdioIntegration(
            connection,
            closeIntegration,
            shutdownTimeoutMillis,
        )
        val hook = Thread({ lifetime.close() }, "kast-codex-stdio-shutdown")
        var hookRegistered = false
        return try {
            val result = try {
                shutdownHooks.register(hook)
                hookRegistered = true
                bridge(connection)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                CodexIntegrationRun.Rejected(CodexIntegrationFailure.INTERRUPTED)
            } catch (_: IllegalStateException) {
                CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED)
            } catch (_: SecurityException) {
                CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED)
            }
            val closed = when (lifetime.close()) {
                DesktopStdioShutdown.COMPLETED -> result
                DesktopStdioShutdown.CONNECTION_REJECTED,
                DesktopStdioShutdown.CONNECTION_TIMED_OUT,
                DesktopStdioShutdown.SERVER_TIMED_OUT,
                DesktopStdioShutdown.FAILED,
                    -> CodexIntegrationRun.Rejected(
                        CodexIntegrationFailure.SHUTDOWN_REJECTED,
                    )
            }
            if (hookRegistered) {
                try {
                    shutdownHooks.remove(hook)
                } catch (_: IllegalStateException) {
                    return CodexIntegrationRun.Rejected(
                        CodexIntegrationFailure.SHUTDOWN_REJECTED,
                    )
                } catch (_: SecurityException) {
                    return CodexIntegrationRun.Rejected(
                        CodexIntegrationFailure.SHUTDOWN_REJECTED,
                    )
                }
                hookRegistered = false
            }
            closed
        } finally {
            try {
                lifetime.close()
            } finally {
                if (hookRegistered) {
                    try {
                        shutdownHooks.remove(hook)
                    } catch (_: IllegalStateException) {
                        // JVM shutdown still owns its registered hook.
                    } catch (_: SecurityException) {
                        // The closed lifecycle result is already retained.
                    }
                }
            }
        }
    }

    private suspend fun rejectAfterClosing(
        failure: CodexIntegrationFailure,
        closeIntegration: suspend () -> Unit,
    ): CodexIntegrationRun = try {
        withTimeout(shutdownTimeoutMillis) { closeIntegration() }
        CodexIntegrationRun.Rejected(failure)
    } catch (_: Exception) {
        CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED)
    }

    private companion object {
        val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = BrokerOperationalLimits.desktopShutdown.value
    }

    private suspend fun bridge(
        connection: BrokerUpstreamConnection,
    ): CodexIntegrationRun = coroutineScope {
        val downstream = async(Dispatchers.IO) { pumpDownstream(connection) }
        val upstream = async(Dispatchers.IO) { pumpUpstream(connection) }
        val result = try {
            select {
                downstream.onAwait { completion -> completion.integrationRun() }
                upstream.onAwait { completion -> completion.integrationRun() }
            }
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
                // Pump completion already owns the closed stdio outcome.
            } catch (_: SecurityException) {
                // Pump completion already owns the closed stdio outcome.
            }
            downstream.cancelAndJoin()
            upstream.cancelAndJoin()
        }
        result
    }

    private suspend fun pumpDownstream(
        connection: BrokerUpstreamConnection,
    ): StdioPumpCompletion {
        while (true) {
            when (val line = runInterruptible { readBoundedLine(input, maximumMessageBytes) }) {
                is BoundedJsonLine.Read -> if (
                    connection.send(line.value) != BrokerUpstreamSend.SENT
                ) {
                    return StdioPumpCompletion.UpstreamRejected
                }
                BoundedJsonLine.Closed -> return StdioPumpCompletion.ParentClosed
                BoundedJsonLine.Rejected -> return StdioPumpCompletion.StdioRejected
            }
        }
    }

    private suspend fun pumpUpstream(
        connection: BrokerUpstreamConnection,
    ): StdioPumpCompletion {
        while (true) {
            when (val frame = connection.receive()) {
                is BrokerUpstreamFrame.Text -> if (!writeJsonLine(frame.message)) {
                    return StdioPumpCompletion.StdioRejected
                }
                BrokerUpstreamFrame.Closed,
                BrokerUpstreamFrame.Rejected,
                    -> return StdioPumpCompletion.UpstreamRejected
            }
        }
    }

    private suspend fun writeJsonLine(message: String): Boolean = runInterruptible {
        if (
            '\n' in message || '\r' in message ||
            message.toByteArray(StandardCharsets.UTF_8).size > maximumMessageBytes
        ) {
            return@runInterruptible false
        }
        try {
            output.write(message.toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private sealed interface StdioPumpCompletion {
    data object ParentClosed : StdioPumpCompletion
    data object UpstreamRejected : StdioPumpCompletion
    data object StdioRejected : StdioPumpCompletion
}

private fun StdioPumpCompletion.integrationRun(): CodexIntegrationRun = when (this) {
    StdioPumpCompletion.ParentClosed -> CodexIntegrationRun.Completed(0)
    StdioPumpCompletion.UpstreamRejected -> CodexIntegrationRun.Rejected(
        CodexIntegrationFailure.UPSTREAM_EXITED,
    )
    StdioPumpCompletion.StdioRejected -> CodexIntegrationRun.Rejected(
        CodexIntegrationFailure.STDIO_REJECTED,
    )
}

private sealed interface BoundedJsonLine {
    data class Read(val value: String) : BoundedJsonLine
    data object Closed : BoundedJsonLine
    data object Rejected : BoundedJsonLine
}

private fun readBoundedLine(
    input: InputStream,
    maximumBytes: Int,
): BoundedJsonLine {
    val bytes = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    return try {
        while (true) {
            when (val next = input.read()) {
                -1 -> return if (bytes.size() == 0) {
                    BoundedJsonLine.Closed
                } else {
                    BoundedJsonLine.Rejected
                }
                '\n'.code -> {
                    val framed = bytes.toByteArray().let { value ->
                        if (value.lastOrNull() == '\r'.code.toByte()) {
                            value.copyOf(value.size - 1)
                        } else {
                            value
                        }
                    }
                    if (framed.isEmpty()) return BoundedJsonLine.Rejected
                    val decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    return BoundedJsonLine.Read(decoder.decode(ByteBuffer.wrap(framed)).toString())
                }
                else -> {
                    if (bytes.size() >= maximumBytes) return BoundedJsonLine.Rejected
                    bytes.write(next)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        BoundedJsonLine.Rejected
    } catch (_: IOException) {
        BoundedJsonLine.Rejected
    } catch (_: IllegalArgumentException) {
        BoundedJsonLine.Rejected
    }
}

private enum class DesktopStdioShutdown {
    COMPLETED,
    CONNECTION_REJECTED,
    CONNECTION_TIMED_OUT,
    SERVER_TIMED_OUT,
    FAILED,
}

/** Concurrent shutdown joins one connection-and-server release transition. */
private class OwnedDesktopStdioIntegration(
    private val connection: BrokerUpstreamConnection,
    private val closeIntegration: suspend () -> Unit,
    private val shutdownTimeoutMillis: Long,
) {
    private sealed interface State {
        data object Open : State
        data class Released(val result: DesktopStdioShutdown) : State
    }

    private var state: State = State.Open

    @Synchronized
    fun close(): DesktopStdioShutdown {
        val current = state
        if (current is State.Released) return current.result
        var result = DesktopStdioShutdown.COMPLETED
        try {
            runBlocking {
                try {
                    withTimeout(shutdownTimeoutMillis) { connection.close() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    result = DesktopStdioShutdown.CONNECTION_TIMED_OUT
                } catch (_: Exception) {
                    result = DesktopStdioShutdown.CONNECTION_REJECTED
                } finally {
                    try {
                        withTimeout(shutdownTimeoutMillis) { closeIntegration() }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        result = DesktopStdioShutdown.SERVER_TIMED_OUT
                    } catch (_: Exception) {
                        result = DesktopStdioShutdown.FAILED
                    }
                }
            }
        } catch (_: CancellationException) {
            result = DesktopStdioShutdown.FAILED
        }
        state = State.Released(result)
        return result
    }
}
