package io.github.amichne.kast.cli.broker.runtime

import io.github.amichne.kast.cli.broker.provider.BrokerExecutable
import io.github.amichne.kast.kernel.Refinement
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ManagedCodexUpstreamTest {
    @Test
    fun `managed upstream launches exact Codex UDS and exposes independent WebSocket connection`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-${UUID.randomUUID()}.sock")
        val launcher = EchoCodexLauncher()
        val options = ManagedCodexUpstreamOptions(
            executable = BrokerExecutable.admit(codex).refinedValue(),
            codexHome = codexHome,
            privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
            launcher = launcher,
            maximumMessageBytes = 1024 * 1024,
            startupTimeoutMillis = 5_000,
        )

        val started = ManagedCodexUpstream.start(options) as ManagedCodexUpstreamStart.Started
        val connection = (
            started.upstream.connect() as BrokerUpstreamConnectionAdmission.Connected
        ).connection
        try {
            assertEquals(BrokerUpstreamSend.SENT, connection.send("hello"))
            assertEquals(BrokerUpstreamFrame.Text("hello"), connection.receive())
        } finally {
            connection.close()
            started.upstream.close()
        }

        assertEquals(
            listOf("app-server", "--listen", "unix://$socket"),
            launcher.request!!.arguments,
        )
        assertEquals(codexHome.toString(), launcher.request!!.environment["CODEX_HOME"])
        assertEquals(true, launcher.closed)
        assertFalse(Files.exists(socket))
    }

    @Test
    fun `startup timeout bounds a stalled unix websocket upgrade`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-stall-${UUID.randomUUID()}.sock")
        val launcher = StallingCodexLauncher()
        try {
            val result = withTimeout(2_000) {
                ManagedCodexUpstream.start(
                    ManagedCodexUpstreamOptions(
                        executable = BrokerExecutable.admit(codex).refinedValue(),
                        codexHome = codexHome,
                        privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                        launcher = launcher,
                        maximumMessageBytes = 1024,
                        startupTimeoutMillis = 100,
                    ),
                )
            }

            assertEquals(
                ManagedCodexUpstreamStart.Rejected(
                    ManagedCodexUpstreamFailure.STARTUP_TIMED_OUT,
                ),
                result,
            )
        } finally {
            launcher.close()
            Files.deleteIfExists(socket)
        }
    }

    private class EchoCodexLauncher : CodexAppServerProcessLauncher {
        var request: CodexAppServerProcessRequest? = null
        var closed = false

        override suspend fun launch(
            request: CodexAppServerProcessRequest,
        ): CodexAppServerProcessAdmission {
            this.request = request
            val engine = embeddedServer(
                factory = CIO,
                configure = { unixConnector(request.socket.toString()) },
                module = {
                    install(WebSockets)
                    routing {
                        webSocket("/") {
                            for (frame in incoming) {
                                val text = (frame as? Frame.Text)?.readText() ?: break
                                send(text)
                            }
                        }
                    }
                },
            )
            engine.startSuspend(wait = false)
            return CodexAppServerProcessAdmission.Started(
                object : CodexAppServerProcess {
                    override val pid: Long = 1234
                    override fun isAlive(): Boolean = true
                    override suspend fun close() {
                        engine.stopSuspend(0, 1_000)
                        closed = true
                    }
                },
            )
        }
    }

    private class StallingCodexLauncher : CodexAppServerProcessLauncher {
        private val alive = AtomicBoolean(false)
        private val release = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private var server: ServerSocketChannel? = null

        override suspend fun launch(
            request: CodexAppServerProcessRequest,
        ): CodexAppServerProcessAdmission {
            val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            listener.bind(UnixDomainSocketAddress.of(request.socket))
            server = listener
            alive.set(true)
            executor.execute {
                try {
                    listener.accept().use { release.await() }
                } catch (_: Exception) {
                    // Closing the synthetic process releases the blocking transport.
                }
            }
            return CodexAppServerProcessAdmission.Started(
                object : CodexAppServerProcess {
                    override val pid: Long = 5678
                    override fun isAlive(): Boolean = alive.get()
                    override suspend fun close() = this@StallingCodexLauncher.close()
                },
            )
        }

        fun close() {
            if (!alive.compareAndSet(true, false)) return
            release.countDown()
            server?.close()
            executor.shutdownNow()
        }
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }

    private fun <Strong, Failure> io.github.amichne.kast.kernel.Validation<Strong, Failure>
        .validatedValue(): Strong = when (this) {
        is io.github.amichne.kast.kernel.Validation.Validated -> value
        is io.github.amichne.kast.kernel.Validation.Rejected ->
            throw AssertionError("Expected validation, received $failures")
    }
}
