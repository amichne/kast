package io.github.amichne.kast.cli.broker.runtime

import io.github.amichne.kast.cli.broker.provider.BrokerExecutable
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ManagedCodexUpstreamOptions(
    val executable: BrokerExecutable,
    val codexHome: Path,
    val privateSocket: BrokerSocketPath,
    val launcher: CodexAppServerProcessLauncher = JdkCodexAppServerProcessLauncher,
    val maximumMessageBytes: Int,
    val startupTimeoutMillis: Long,
)

internal data class CodexAppServerProcessRequest(
    val executable: BrokerExecutable,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val socket: Path,
)

internal interface CodexAppServerProcess {
    val pid: Long
    fun isAlive(): Boolean
    suspend fun close()
}

internal sealed interface CodexAppServerProcessAdmission {
    data class Started(val process: CodexAppServerProcess) : CodexAppServerProcessAdmission
    data object Rejected : CodexAppServerProcessAdmission
}

internal fun interface CodexAppServerProcessLauncher {
    suspend fun launch(request: CodexAppServerProcessRequest): CodexAppServerProcessAdmission
}

internal enum class ManagedCodexUpstreamFailure {
    INVALID_OPTIONS,
    SOCKET_PATH_OWNED,
    SOCKET_PATH_REJECTED,
    PROCESS_START_REJECTED,
    STARTUP_TIMED_OUT,
    SOCKET_IDENTITY_REJECTED,
    INTERRUPTED,
}

internal sealed interface ManagedCodexUpstreamStart {
    data class Started(val upstream: ManagedCodexUpstream) : ManagedCodexUpstreamStart
    data class Rejected(val failure: ManagedCodexUpstreamFailure) : ManagedCodexUpstreamStart
}

internal enum class ManagedCodexUpstreamTermination { CLOSED, PROCESS_EXITED }

internal class ManagedCodexUpstream private constructor(
    private val process: CodexAppServerProcess,
    private val privateSocket: BrokerSocketPath,
    private val maximumMessageBytes: Int,
    private val connectionTimeoutMillis: Long,
    private val ownedSocket: OwnedUnixSocket,
) : BrokerUpstreamConnector {
    private val connections = ConcurrentHashMap.newKeySet<ManagedUpstreamConnection>()
    private val closed = AtomicBoolean(false)

    override suspend fun connect(): BrokerUpstreamConnectionAdmission {
        if (closed.get() || !process.isAlive()) return BrokerUpstreamConnectionAdmission.Rejected
        val connected = connectUnixWebSocket(
            privateSocket.path,
            maximumMessageBytes,
            connectionTimeoutMillis,
        )
        val connection = (connected as? BrokerUpstreamConnectionAdmission.Connected)?.connection
            ?: return BrokerUpstreamConnectionAdmission.Rejected
        lateinit var managed: ManagedUpstreamConnection
        managed = ManagedUpstreamConnection(connection) { connections.remove(managed) }
        connections.add(managed)
        return BrokerUpstreamConnectionAdmission.Connected(managed)
    }

    internal suspend fun awaitTermination(): ManagedCodexUpstreamTermination {
        while (!closed.get() && process.isAlive()) delay(PROCESS_HEALTH_POLL_MILLIS)
        return if (closed.get()) {
            ManagedCodexUpstreamTermination.CLOSED
        } else {
            ManagedCodexUpstreamTermination.PROCESS_EXITED
        }
    }

    internal suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        connections.toList().forEach { connection -> connection.close() }
        connections.clear()
        try {
            process.close()
        } finally {
            ownedSocket.retire()
        }
    }

    companion object {
        internal suspend fun start(options: ManagedCodexUpstreamOptions): ManagedCodexUpstreamStart {
            if (
                options.maximumMessageBytes <= 0 || options.startupTimeoutMillis <= 0 ||
                !options.codexHome.isAbsolute || options.codexHome.normalize() != options.codexHome ||
                !Files.isDirectory(options.codexHome)
            ) {
                return rejected(ManagedCodexUpstreamFailure.INVALID_OPTIONS)
            }
            when (UnixSocketPathOwnership.prepare(options.privateSocket.path)) {
                UnixSocketPathPreparation.PREPARED -> Unit
                UnixSocketPathPreparation.OWNED -> return rejected(
                    ManagedCodexUpstreamFailure.SOCKET_PATH_OWNED,
                )
                UnixSocketPathPreparation.REJECTED,
                UnixSocketPathPreparation.PARENT_REJECTED,
                    -> return rejected(ManagedCodexUpstreamFailure.SOCKET_PATH_REJECTED)
            }
            val request = CodexAppServerProcessRequest(
                executable = options.executable,
                arguments = listOf(
                    "app-server",
                    "--listen",
                    "unix://${options.privateSocket.path}",
                ),
                environment = mapOf("CODEX_HOME" to options.codexHome.toString()),
                socket = options.privateSocket.path,
            )
            val process = when (val admission = options.launcher.launch(request)) {
                is CodexAppServerProcessAdmission.Started -> admission.process
                CodexAppServerProcessAdmission.Rejected -> return rejected(
                    ManagedCodexUpstreamFailure.PROCESS_START_REJECTED,
                )
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                options.startupTimeoutMillis,
            )
            try {
                while (System.nanoTime() < deadline && process.isAlive()) {
                    val remainingNanos = deadline - System.nanoTime()
                    if (remainingNanos <= 0) break
                    val remainingMillis = maxOf(
                        1L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                    )
                    val probe = connectUnixWebSocket(
                        options.privateSocket.path,
                        options.maximumMessageBytes,
                        remainingMillis,
                    )
                    val connection = (probe as? BrokerUpstreamConnectionAdmission.Connected)
                        ?.connection
                    if (connection != null) {
                        connection.close()
                        Files.setPosixFilePermissions(
                            options.privateSocket.path,
                            PosixFilePermissions.fromString("rw-------"),
                        )
                        val owned = OwnedUnixSocket.capture(options.privateSocket.path)
                        if (owned == null) {
                            process.close()
                            return rejected(ManagedCodexUpstreamFailure.SOCKET_IDENTITY_REJECTED)
                        }
                        return ManagedCodexUpstreamStart.Started(
                            ManagedCodexUpstream(
                                process,
                                options.privateSocket,
                                options.maximumMessageBytes,
                                options.startupTimeoutMillis,
                                owned,
                            ),
                        )
                    }
                    delay(minOf(PROCESS_HEALTH_POLL_MILLIS, remainingMillis))
                }
            } catch (cancelled: CancellationException) {
                process.close()
                throw cancelled
            } catch (_: InterruptedException) {
                process.close()
                Thread.currentThread().interrupt()
                return rejected(ManagedCodexUpstreamFailure.INTERRUPTED)
            } catch (_: IOException) {
                process.close()
                return rejected(ManagedCodexUpstreamFailure.SOCKET_IDENTITY_REJECTED)
            } catch (_: SecurityException) {
                process.close()
                return rejected(ManagedCodexUpstreamFailure.SOCKET_IDENTITY_REJECTED)
            }
            process.close()
            return rejected(ManagedCodexUpstreamFailure.STARTUP_TIMED_OUT)
        }

        private fun rejected(
            failure: ManagedCodexUpstreamFailure,
        ): ManagedCodexUpstreamStart.Rejected = ManagedCodexUpstreamStart.Rejected(failure)

        private const val PROCESS_HEALTH_POLL_MILLIS = 25L
    }
}

private class ManagedUpstreamConnection(
    private val delegate: BrokerUpstreamConnection,
    private val onClose: () -> Unit,
) : BrokerUpstreamConnection {
    private val closed = AtomicBoolean(false)

    override suspend fun send(message: String): BrokerUpstreamSend = delegate.send(message)

    override suspend fun receive(): BrokerUpstreamFrame = delegate.receive()

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}

private suspend fun connectUnixWebSocket(
    socket: Path,
    maximumMessageBytes: Int,
    timeoutMillis: Long,
): BrokerUpstreamConnectionAdmission {
    val client = HttpClient(CIO) {
        install(WebSockets) { maxFrameSize = maximumMessageBytes.toLong() }
    }
    return try {
        val session = withTimeoutOrNull(timeoutMillis) {
            client.webSocketSession {
                url("ws://localhost/")
                unixSocket(socket.toString())
            }
        } ?: return BrokerUpstreamConnectionAdmission.Rejected.also { client.close() }
        BrokerUpstreamConnectionAdmission.Connected(
            KtorCodexUpstreamConnection(client, session, maximumMessageBytes),
        )
    } catch (cancelled: CancellationException) {
        client.close()
        throw cancelled
    } catch (_: Exception) {
        client.close()
        BrokerUpstreamConnectionAdmission.Rejected
    }
}

private class KtorCodexUpstreamConnection(
    private val client: HttpClient,
    private val session: DefaultClientWebSocketSession,
    private val maximumMessageBytes: Int,
) : BrokerUpstreamConnection {
    private val closed = AtomicBoolean(false)

    override suspend fun send(message: String): BrokerUpstreamSend = try {
        if (message.toByteArray(Charsets.UTF_8).size > maximumMessageBytes) {
            BrokerUpstreamSend.REJECTED
        } else {
            session.send(message)
            BrokerUpstreamSend.SENT
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BrokerUpstreamSend.REJECTED
    }

    override suspend fun receive(): BrokerUpstreamFrame = try {
        when (val frame = session.incoming.receiveCatching().getOrNull()) {
            is Frame.Text -> {
                val message = frame.readText()
                if (message.toByteArray(Charsets.UTF_8).size <= maximumMessageBytes) {
                    BrokerUpstreamFrame.Text(message)
                } else {
                    BrokerUpstreamFrame.Rejected
                }
            }
            null, is Frame.Close -> BrokerUpstreamFrame.Closed
            else -> BrokerUpstreamFrame.Rejected
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BrokerUpstreamFrame.Rejected
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            session.close()
        } finally {
            client.close()
        }
    }
}

private object JdkCodexAppServerProcessLauncher : CodexAppServerProcessLauncher {
    override suspend fun launch(
        request: CodexAppServerProcessRequest,
    ): CodexAppServerProcessAdmission = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(
                listOf(request.executable.path.toString()) + request.arguments,
            )
                .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE.toFile()))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .also { builder -> builder.environment().putAll(request.environment) }
                .start()
        } catch (_: IOException) {
            return@withContext CodexAppServerProcessAdmission.Rejected
        } catch (_: SecurityException) {
            return@withContext CodexAppServerProcessAdmission.Rejected
        }
        CodexAppServerProcessAdmission.Started(JdkCodexAppServerProcess(process))
    }

    private val NULL_DEVICE = Path.of("/dev/null")
}

private class JdkCodexAppServerProcess(
    private val process: Process,
) : CodexAppServerProcess {
    override val pid: Long = process.pid()

    override fun isAlive(): Boolean = process.isAlive

    override suspend fun close() = withContext(Dispatchers.IO) {
        if (!process.isAlive) return@withContext
        process.destroy()
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }
}
