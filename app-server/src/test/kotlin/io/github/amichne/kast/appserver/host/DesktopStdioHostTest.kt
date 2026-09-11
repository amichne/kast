package io.github.amichne.kast.appserver.host

import io.github.amichne.kast.appserver.CodexIntegrationFailure
import io.github.amichne.kast.appserver.CodexIntegrationRun
import io.github.amichne.kast.appserver.CodexIntegrationShutdownHooks
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DesktopStdioHostTest {
    @Test
    fun `stdio preserves one json line in each direction before upstream exit`() = runBlocking {
        val downstreamInput = PipedInputStream()
        val desktop = PipedOutputStream(downstreamInput)
        val downstreamOutput = ByteArrayOutputStream()
        val connection = EchoThenCloseConnection()
        val serverClosed = AtomicInteger()
        val host =
            DesktopStdioHost(
                input = downstreamInput,
                output = downstreamOutput,
                connector = { BrokerUpstreamConnectionAdmission.Connected(connection) },
                maximumMessageBytes = 1_024,
                shutdownHooks = CapturedShutdownHooks(),
            )
        val request = "{\"method\":\"initialize\",\"id\":1,\"params\":{}}"
        val response = "{\"id\":1,\"result\":{\"userAgent\":\"codex\"}}"
        connection.response = response

        val running = launch {
            assertEquals(
                CodexIntegrationRun.Rejected(CodexIntegrationFailure.UPSTREAM_EXITED),
                host.run { serverClosed.incrementAndGet() },
            )
        }
        desktop.write("$request\n".toByteArray())
        desktop.flush()
        running.join()
        desktop.close()

        assertEquals(listOf(request), connection.sent)
        assertEquals("$response\n", downstreamOutput.toString(Charsets.UTF_8))
        assertEquals(1, serverClosed.get())
        assertTrue(connection.closed)
    }

    @Test
    fun `closed parent stdio completes and oversized input fails closed`() = runBlocking {
        val closedServer = AtomicInteger()
        val unusedConnection = RecordingConnection()
        val completed =
            DesktopStdioHost(
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                    connector = { BrokerUpstreamConnectionAdmission.Connected(unusedConnection) },
                    maximumMessageBytes = 32,
                    shutdownHooks = CapturedShutdownHooks(),
                )
                .run(closeIntegration = { closedServer.incrementAndGet() })

        assertEquals(CodexIntegrationRun.Completed(0), completed)
        assertEquals(1, closedServer.get())
        assertTrue(unusedConnection.closed)

        val rejectedConnection = RecordingConnection()
        val rejected =
            DesktopStdioHost(
                    input = ByteArrayInputStream("{\"value\":\"${"x".repeat(64)}\"}\n".toByteArray()),
                    output = ByteArrayOutputStream(),
                    connector = { BrokerUpstreamConnectionAdmission.Connected(rejectedConnection) },
                    maximumMessageBytes = 32,
                    shutdownHooks = CapturedShutdownHooks(),
                )
                .run(closeIntegration = {})

        assertEquals(
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.STDIO_REJECTED),
            rejected,
        )
        assertTrue(rejectedConnection.sent.isEmpty())
    }

    @Test
    fun `cleanup failure replaces an otherwise successful stdio completion`() = runBlocking {
        val closedServer = AtomicInteger()
        val connection =
            object : RecordingConnection() {
                override suspend fun close() {
                    super.close()
                    error("synthetic connection cleanup rejection")
                }
            }

        val completed =
            DesktopStdioHost(
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                    connector = { BrokerUpstreamConnectionAdmission.Connected(connection) },
                    maximumMessageBytes = 32,
                    shutdownHooks = CapturedShutdownHooks(),
                )
                .run(closeIntegration = { closedServer.incrementAndGet() })

        assertEquals(
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED),
            completed,
        )
        assertEquals(1, closedServer.get())
    }

    @Test
    fun `transport rejection retains cleanup failure as a closed host outcome`() = runBlocking {
        val result =
            DesktopStdioHost(
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                    connector = { BrokerUpstreamConnectionAdmission.Rejected },
                    maximumMessageBytes = 32,
                    shutdownHooks = CapturedShutdownHooks(),
                )
                .run(closeIntegration = { error("synthetic server cleanup rejection") })

        assertEquals(
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED),
            result,
        )
    }

    @Test
    fun `suspending connection cleanup is bounded as a closed shutdown rejection`() = runBlocking {
        val connection =
            object : RecordingConnection() {
                override suspend fun close() = awaitCancellation()
            }
        val result =
            DesktopStdioHost(
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                    connector = { BrokerUpstreamConnectionAdmission.Connected(connection) },
                    maximumMessageBytes = 32,
                    shutdownHooks = CapturedShutdownHooks(),
                    shutdownTimeoutMillis = 25,
                )
                .run(closeIntegration = {})

        assertEquals(
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED),
            result,
        )
    }

    @Test
    fun `normal completion joins shutdown hook cleanup and retains its failure`() {
        val downstreamInput = PipedInputStream()
        val desktop = PipedOutputStream(downstreamInput)
        val connection =
            object : RecordingConnection() {
                override suspend fun close() {
                    super.close()
                    error("synthetic connection cleanup rejection")
                }
            }
        val hooks = CapturedShutdownHooks()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val host =
            DesktopStdioHost(
                input = downstreamInput,
                output = ByteArrayOutputStream(),
                connector = { BrokerUpstreamConnectionAdmission.Connected(connection) },
                maximumMessageBytes = 32,
                shutdownHooks = hooks,
            )
        val running =
            pool.submit<CodexIntegrationRun> {
                runBlocking {
                    host.run {
                        closing.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
        try {
            val hook = hooks.registered.get(5, TimeUnit.SECONDS)
            val shutdown = pool.submit { hook.run() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            desktop.close()
            assertThrows<TimeoutException> {
                running.get(100, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            shutdown.get(5, TimeUnit.SECONDS)
            assertEquals(
                CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED),
                running.get(5, TimeUnit.SECONDS),
            )
        } finally {
            release.countDown()
            desktop.close()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `shutdown hook registration rejection remains finite data`() = runBlocking {
        val result =
            DesktopStdioHost(
                    input = ByteArrayInputStream(byteArrayOf()),
                    output = ByteArrayOutputStream(),
                    connector = { BrokerUpstreamConnectionAdmission.Connected(RecordingConnection()) },
                    maximumMessageBytes = 32,
                    shutdownHooks =
                        object : CodexIntegrationShutdownHooks {
                            override fun register(hook: Thread) {
                                throw SecurityException("synthetic hook policy")
                            }

                            override fun remove(hook: Thread) = Unit
                        },
                )
                .run(closeIntegration = {})

        assertEquals(
            CodexIntegrationRun.Rejected(CodexIntegrationFailure.SHUTDOWN_REJECTED),
            result,
        )
    }

    private class EchoThenCloseConnection : RecordingConnection() {
        lateinit var response: String

        override suspend fun send(message: String): BrokerUpstreamSend {
            super.send(message)
            incoming.send(BrokerUpstreamFrame.Text(response))
            incoming.send(BrokerUpstreamFrame.Closed)
            return BrokerUpstreamSend.SENT
        }
    }

    private open class RecordingConnection : BrokerUpstreamConnection {
        val sent = mutableListOf<String>()
        protected val incoming = Channel<BrokerUpstreamFrame>(Channel.UNLIMITED)
        var closed = false

        override suspend fun send(message: String): BrokerUpstreamSend {
            sent += message
            return BrokerUpstreamSend.SENT
        }

        override suspend fun receive(): BrokerUpstreamFrame =
            incoming.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed

        override suspend fun close() {
            closed = true
            incoming.close()
        }
    }

    private class CapturedShutdownHooks : CodexIntegrationShutdownHooks {
        val registered = CompletableFuture<Thread>()

        override fun register(hook: Thread) {
            registered.complete(hook)
        }

        override fun remove(hook: Thread) = Unit
    }
}
