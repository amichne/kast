package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeCodexReadinessTest {
    @Test
    fun `readiness completes native initialized only for the correlated successful response`() = runBlocking {
        val peer = Peer(Reply("kast-protocol-readiness", Result("codex-test")))
        assertEquals(NativeCodexReadiness.READY, NativeCodexReadiness.exchange(peer))
        assertEquals(
            listOf("initialize", "initialized"),
            peer.sent.map { Json.parseToJsonElement(it).jsonObject.getValue("method").jsonPrimitive.content },
        )
        val request = Json.parseToJsonElement(peer.sent.first()).jsonObject
        assertEquals("kast-protocol-readiness", request.getValue("id").jsonPrimitive.content)
        assertEquals(
            "kast-readiness",
            request
                .getValue("params")
                .jsonObject
                .getValue("clientInfo")
                .jsonObject
                .getValue("name")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `wrong correlation native errors and ambiguous replies cannot prove readiness`() = runBlocking {
        for (reply in
            listOf(
                Reply("other", Result("codex-test")),
                Reply("kast-protocol-readiness", null, Error(-32600, "rejected")),
                Reply("kast-protocol-readiness", Result("codex-test"), Error(-32600, "rejected")),
                Reply("kast-protocol-readiness", Result("")),
            )) {
            val peer = Peer(reply)
            assertEquals(NativeCodexReadiness.REJECTED, NativeCodexReadiness.exchange(peer))
            assertEquals(1, peer.sent.size)
        }
    }

    private class Peer(private val reply: Reply) : BrokerUpstreamConnection {
        val sent = mutableListOf<String>()

        override suspend fun send(message: String): BrokerUpstreamSend {
            sent += message
            return BrokerUpstreamSend.SENT
        }

        override suspend fun receive(): BrokerUpstreamFrame =
            BrokerUpstreamFrame.Text(Json.encodeToString(Reply.serializer(), reply))

        override suspend fun close() = Unit
    }

    @Serializable private data class Reply(val id: String, val result: Result?, val error: Error? = null)

    @Serializable private data class Result(val userAgent: String)

    @Serializable private data class Error(val code: Int, val message: String)
}
