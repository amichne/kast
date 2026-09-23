package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.host.admission.CodexAppServerArguments
import io.github.amichne.kast.appserver.host.admission.DesktopFacadeExecutables
import io.github.amichne.kast.appserver.host.admission.UpstreamCodexExecutable
import io.github.amichne.kast.kernel.Refinement
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedCodexUpstreamTest {
    @Test
    fun `foreign socket alias survives rejection and clean retry`(@TempDir temporary: Path) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-stale-${UUID.randomUUID()}.sock")
        val foreign = Files.writeString(temporary.resolve("foreign.sock"), "foreign-transport")
        val launcher = EchoCodexLauncher()
        val options =
            ManagedCodexUpstreamOptions(
                executable = UpstreamCodexExecutable.admit(codex, DesktopFacadeExecutables.none()).refinedValue(),
                codexHome = codexHome,
                privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                launcher = launcher,
                maximumMessageBytes = 1024 * 1024,
                startupTimeoutMillis = 5_000,
            )
        Files.createSymbolicLink(socket, foreign)
        try {
            assertEquals(
                ManagedCodexUpstreamStart.Rejected(ManagedCodexUpstreamFailure.SOCKET_PATH_REJECTED),
                ManagedCodexUpstream.start(options),
            )
            assertEquals(null, launcher.request)
            assertEquals(true, Files.isSymbolicLink(socket))
            assertEquals("foreign-transport", Files.readString(foreign))

            Files.delete(socket)
            val started = ManagedCodexUpstream.start(options) as ManagedCodexUpstreamStart.Started
            started.upstream.close()
            assertEquals("foreign-transport", Files.readString(foreign))
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `socket alias held by the launched process connects and retires`(@TempDir temporary: Path) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-alias-${UUID.randomUUID()}.sock")
        val target = Path.of("/private/tmp/kast-codex-target-${UUID.randomUUID()}.sock")
        val launcher = EchoCodexLauncher(target)
        try {
            val result =
                ManagedCodexUpstream.start(
                    ManagedCodexUpstreamOptions(
                        executable =
                            UpstreamCodexExecutable.admit(codex, DesktopFacadeExecutables.none()).refinedValue(),
                        codexHome = codexHome,
                        privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                        launcher = launcher,
                        maximumMessageBytes = 1024 * 1024,
                        startupTimeoutMillis = 5_000,
                    )
                )
            val started = result as ManagedCodexUpstreamStart.Started
            val connection = (started.upstream.connect() as BrokerUpstreamConnectionAdmission.Connected).connection
            assertEquals(BrokerUpstreamSend.SENT, connection.send("alias"))
            assertEquals(BrokerUpstreamFrame.Text("alias"), connection.receive())
            connection.close()
            started.upstream.close()
            assertEquals(true, launcher.closed)
            assertFalse(Files.exists(socket, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `socket alias held by another process does not gain ownership`(@TempDir temporary: Path) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-unowned-${UUID.randomUUID()}.sock")
        val target = Path.of("/private/tmp/kast-codex-foreign-${UUID.randomUUID()}.sock")
        val launcher = EchoCodexLauncher(target, Long.MAX_VALUE)
        try {
            val result =
                ManagedCodexUpstream.start(
                    ManagedCodexUpstreamOptions(
                        executable = UpstreamCodexExecutable.admit(codex, DesktopFacadeExecutables.none()).refinedValue(),
                        codexHome = codexHome,
                        privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                        launcher = launcher,
                        maximumMessageBytes = 1024 * 1024,
                        startupTimeoutMillis = 5_000,
                    )
                )
            assertEquals(
                ManagedCodexUpstreamStart.Rejected(ManagedCodexUpstreamFailure.SOCKET_ALIAS_OWNER_UNPROVEN),
                result,
            )
            assertEquals(true, launcher.closed)
            assertEquals(true, Files.isSymbolicLink(socket))
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `retargeted alias is rejected and not deleted on close`(@TempDir temporary: Path) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-retarget-${UUID.randomUUID()}.sock")
        val target = Path.of("/private/tmp/kast-codex-original-${UUID.randomUUID()}.sock")
        val foreign = Files.writeString(temporary.resolve("foreign"), "preserve")
        val launcher = EchoCodexLauncher(target)
        try {
            val started =
                ManagedCodexUpstream.start(
                    ManagedCodexUpstreamOptions(
                        executable = UpstreamCodexExecutable.admit(codex, DesktopFacadeExecutables.none()).refinedValue(),
                        codexHome = codexHome,
                        privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                        launcher = launcher,
                        maximumMessageBytes = 1024 * 1024,
                        startupTimeoutMillis = 5_000,
                    )
                ) as ManagedCodexUpstreamStart.Started
            Files.delete(socket)
            Files.createSymbolicLink(socket, foreign)
            assertEquals(BrokerUpstreamConnectionAdmission.Rejected, started.upstream.connect())
            started.upstream.close()
            assertEquals(true, Files.isSymbolicLink(socket))
            assertEquals("preserve", Files.readString(foreign))
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `installed Codex publishes an owned socket alias`(@TempDir temporary: Path) = runBlocking {
        val configured = System.getenv("KAST_CODEX_ALIAS_ACCEPTANCE_EXECUTABLE")
        assumeTrue(!configured.isNullOrBlank(), "Set KAST_CODEX_ALIAS_ACCEPTANCE_EXECUTABLE for native alias acceptance")
        val codex = Path.of(configured).toRealPath()
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-native-${UUID.randomUUID()}.sock")
        try {
            val started =
                ManagedCodexUpstream.start(
                    ManagedCodexUpstreamOptions(
                        executable = UpstreamCodexExecutable.admit(codex, DesktopFacadeExecutables.none()).refinedValue(),
                        codexHome = codexHome,
                        privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                        maximumMessageBytes = 1024 * 1024,
                        startupTimeoutMillis = 10_000,
                    )
                ) as ManagedCodexUpstreamStart.Started
            try {
                assertEquals(true, Files.isSymbolicLink(socket))
                val connection = started.upstream.connect()
                assertEquals(true, connection is BrokerUpstreamConnectionAdmission.Connected)
                (connection as BrokerUpstreamConnectionAdmission.Connected).connection.close()
            } finally {
                started.upstream.close()
            }
            assertFalse(Files.exists(socket, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `managed upstream launches exact Codex UDS and exposes independent WebSocket connection`(
        @TempDir temporary: Path
    ) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-${UUID.randomUUID()}.sock")
        val launcher = EchoCodexLauncher()
        val options =
            ManagedCodexUpstreamOptions(
                executable =
                    UpstreamCodexExecutable.admit(
                            codex,
                            DesktopFacadeExecutables.none(),
                        )
                        .refinedValue(),
                codexHome = codexHome,
                privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                launcher = launcher,
                maximumMessageBytes = 1024 * 1024,
                startupTimeoutMillis = 5_000,
                appServerArguments =
                    CodexAppServerArguments.admit(
                            listOf("-c", "features.code_mode_host=true"),
                            listOf("--analytics-default-enabled"),
                        )
                        .refinedValue(),
            )

        val started = ManagedCodexUpstream.start(options) as ManagedCodexUpstreamStart.Started
        val connection = (started.upstream.connect() as BrokerUpstreamConnectionAdmission.Connected).connection
        try {
            assertEquals(BrokerUpstreamSend.SENT, connection.send("hello"))
            assertEquals(BrokerUpstreamFrame.Text("hello"), connection.receive())
        } finally {
            connection.close()
            started.upstream.close()
        }

        assertEquals(
            listOf(
                "-c",
                "features.code_mode_host=true",
                "app-server",
                "--analytics-default-enabled",
                "--listen",
                "unix://$socket",
            ),
            launcher.request!!.arguments,
        )
        assertEquals(codexHome.toString(), launcher.request!!.environment["CODEX_HOME"])
        assertEquals(true, launcher.closed)
        assertFalse(Files.exists(socket))
    }

    @Test
    fun `startup timeout bounds a stalled unix websocket upgrade`(@TempDir temporary: Path) = runBlocking {
        val codex = executable(temporary.resolve("codex"))
        val codexHome = Files.createDirectory(temporary.resolve("codex-home")).toRealPath()
        val socket = Path.of("/private/tmp/kast-codex-stall-${UUID.randomUUID()}.sock")
        val launcher = StallingCodexLauncher()
        try {
            val result =
                withTimeout(2_000) {
                    ManagedCodexUpstream.start(
                        ManagedCodexUpstreamOptions(
                            executable =
                                UpstreamCodexExecutable.admit(
                                        codex,
                                        DesktopFacadeExecutables.none(),
                                    )
                                    .refinedValue(),
                            codexHome = codexHome,
                            privateSocket = BrokerSocketPath.admit(socket).validatedValue(),
                            launcher = launcher,
                            maximumMessageBytes = 1024,
                            startupTimeoutMillis = 100,
                        )
                    )
                }

            assertEquals(
                ManagedCodexUpstreamStart.Rejected(ManagedCodexUpstreamFailure.STARTUP_TIMED_OUT),
                result,
            )
        } finally {
            launcher.close()
            Files.deleteIfExists(socket)
        }
    }

    private class EchoCodexLauncher(
        private val aliasTarget: Path? = null,
        private val reportedPid: Long = ProcessHandle.current().pid(),
    ) : CodexAppServerProcessLauncher {
        var request: CodexAppServerProcessRequest? = null
        var closed = false

        override suspend fun launch(request: CodexAppServerProcessRequest): CodexAppServerProcessAdmission {
            this.request = request
            val engine =
                embeddedServer(
                    factory = CIO,
                    configure = { unixConnector((aliasTarget ?: request.socket).toString()) },
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
            if (aliasTarget != null) Files.createSymbolicLink(request.socket, aliasTarget)
            return CodexAppServerProcessAdmission.Started(
                object : CodexAppServerProcess {
                    override val pid: Long = reportedPid

                    override fun isAlive(): Boolean = true

                    override suspend fun close() {
                        engine.stopSuspend(0, 1_000)
                        closed = true
                    }
                }
            )
        }
    }

    private class StallingCodexLauncher : CodexAppServerProcessLauncher {
        private val alive = AtomicBoolean(false)
        private val release = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private var server: ServerSocketChannel? = null

        override suspend fun launch(request: CodexAppServerProcessRequest): CodexAppServerProcessAdmission {
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
                }
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

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
        }

    private fun <Strong, Failure> io.github.amichne.kast.kernel.Validation<Strong, Failure>.validatedValue(): Strong =
        when (this) {
            is io.github.amichne.kast.kernel.Validation.Validated -> value
            is io.github.amichne.kast.kernel.Validation.Rejected ->
                throw AssertionError("Expected validation, received $failures")
        }
}
