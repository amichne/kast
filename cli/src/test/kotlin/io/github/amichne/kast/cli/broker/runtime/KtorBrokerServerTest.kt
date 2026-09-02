package io.github.amichne.kast.cli.broker.runtime

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.cli.broker.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.cli.broker.protocol.MemoryThreadCatalogStore
import io.github.amichne.kast.kernel.Validation
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class KtorBrokerServerTest {
    @Test
    fun `Ktor Unix WebSocket server transparently bridges one upstream session`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val publicSocket = Path.of("/private/tmp/kast-ktor-${UUID.randomUUID()}.sock")
        val upstream = FakeUpstreamConnection()
        val broker = Broker.create(emptyList(), BrokerLimits.defaults()).validatedValue()
        val contracts = protocolContracts()
        val started = KtorBrokerServer.start(
            KtorBrokerServerOptions(
                publicSocket = BrokerSocketPath.admit(publicSocket).validatedValue(),
                broker = broker,
                contracts = contracts,
                threadStore = MemoryThreadCatalogStore(),
                upstream = BrokerUpstreamConnector {
                    BrokerUpstreamConnectionAdmission.Connected(upstream)
                },
                maximumConnections = 2,
                maximumMessageBytes = 4 * 1_024 * 1_024,
            ),
        ) as KtorBrokerServerStart.Started
        val client = HttpClient(CIO) { install(WebSockets) }
        val request = " { \"id\" : 1, \"method\" : \"model/list\", \"params\" : {} } "
        val response = " { \"id\" : 1, \"result\" : {\"data\": []} } "
        try {
            client.webSocket({
                url("ws://localhost/")
                unixSocket(publicSocket.toString())
            }) {
                send("""{"id":0,"method":"initialize","params":{"clientInfo":{"name":"test"}}}""")
                val refinedInitialize = upstream.sentByBroker.receive()
                assertTrue(refinedInitialize.contains("\"experimentalApi\":true"))
                val initializeResponse = """{"id":0,"result":{}}"""
                upstream.receivedFromUpstream.send(
                    BrokerUpstreamFrame.Text(initializeResponse),
                )
                assertEquals(initializeResponse, (incoming.receive() as Frame.Text).readText())
                send(request)
                assertEquals(request, upstream.sentByBroker.receive())
                upstream.receivedFromUpstream.send(BrokerUpstreamFrame.Text(response))
                assertEquals(response, (incoming.receive() as Frame.Text).readText())
            }
        } finally {
            client.close()
            started.server.close()
        }

        assertFalse(Files.exists(publicSocket))
    }

    @Test
    fun `connection closes when initialize is not the first request`() = runBlocking {
        val publicSocket = Path.of("/private/tmp/kast-ktor-${UUID.randomUUID()}.sock")
        val upstream = FakeUpstreamConnection()
        val started = KtorBrokerServer.start(
            KtorBrokerServerOptions(
                publicSocket = BrokerSocketPath.admit(publicSocket).validatedValue(),
                broker = Broker.create(emptyList(), BrokerLimits.defaults()).validatedValue(),
                contracts = protocolContracts(),
                threadStore = MemoryThreadCatalogStore(),
                upstream = BrokerUpstreamConnector {
                    BrokerUpstreamConnectionAdmission.Connected(upstream)
                },
                maximumConnections = 1,
                maximumMessageBytes = 1_024,
                connectionInitializationTimeoutMillis = 250,
            ),
        ) as KtorBrokerServerStart.Started
        val client = HttpClient(CIO) { install(WebSockets) }
        try {
            withTimeout(1_000) {
                client.webSocket({
                    url("ws://localhost/")
                    unixSocket(publicSocket.toString())
                }) {
                    send("""{"id":1,"method":"model/list","params":{}}""")
                    incoming.receiveCatching()
                }
            }
            assertNull(withTimeoutOrNull(50) { upstream.sentByBroker.receive() })
        } finally {
            client.close()
            started.server.close()
        }
    }

    private class FakeUpstreamConnection : BrokerUpstreamConnection {
        val sentByBroker = Channel<String>(Channel.UNLIMITED)
        val receivedFromUpstream = Channel<BrokerUpstreamFrame>(Channel.UNLIMITED)

        override suspend fun send(message: String): BrokerUpstreamSend {
            sentByBroker.send(message)
            return BrokerUpstreamSend.SENT
        }

        override suspend fun receive(): BrokerUpstreamFrame =
            receivedFromUpstream.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed

        override suspend fun close() {
            receivedFromUpstream.close()
            sentByBroker.close()
        }
    }

    private fun protocolContracts(): CodexProtocolContracts {
        val schema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        return CodexProtocolContracts.define(
            CodexOwnedSchema.entries.associateWith { schema },
        ).validatedValue()
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong = when (this) {
        is Validation.Validated -> value
        is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
    }
}
