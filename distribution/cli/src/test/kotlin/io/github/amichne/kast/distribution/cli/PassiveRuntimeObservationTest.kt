package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class TestRuntimeReply(val type: String, val value: String)

class PassiveRuntimeObservationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `passive management request includes required type and protocol version`() {
        val request = Json.parseToJsonElement(runtimeStatusRequestDocument()).jsonObject
        assertEquals(setOf("type", "version"), request.keys)
        assertEquals("status", request.getValue("type").jsonPrimitive.content)
        assertEquals("1", request.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun `mixed versions retain each live host identity and proven compatibility`() {
        val first =
            HostedServiceStatus.Compatible("/workspace/one", "00000000-0000-0000-0000-000000000001", 123, "0.49.0")
        val second =
            HostedServiceStatus.Compatible("/workspace/two", "00000000-0000-0000-0000-000000000002", 456, "0.48.9")
        val observation = projectHostedServices(listOf(first, second))
        assertEquals(ObservationState.VERIFIED, observation.state)
        assertEquals(listOf(first, second), observation.value)
    }

    @Test
    fun `missing live host evidence remains unavailable`() {
        assertEquals(ObservationState.UNAVAILABLE, projectHostedServices(null).state)
        assertEquals(ObservationState.UNAVAILABLE, projectHostedServices(emptyList()).state)
        val unavailable =
            HostedServiceStatus.Unavailable("/workspace", HostedServiceUnavailableFailure.HOST_UNAVAILABLE)
        assertEquals(listOf(unavailable), projectHostedServices(listOf(unavailable)).value)
    }

    @Test
    fun `unproven host identity or provenance rejects the observation`() {
        val valid = HostedServiceStatus.Compatible("/workspace", "00000000-0000-0000-0000-000000000001", 123, "0.49.0")
        listOf(
                valid.copy(host = "not-a-host"),
                valid.copy(hostPid = 0),
                valid.copy(hostedPluginVersion = "unknown"),
                valid.copy(root = "relative"),
            )
            .forEach { assertEquals(ObservationState.UNAVAILABLE, projectHostedServices(listOf(it)).state) }
    }

    @Test
    fun `passive Unix websocket exchange reads one bounded status response`() {
        val socket = temporary.resolve("management.sock")
        val reply = Json.encodeToString(TestRuntimeReply("status", "ready".repeat(32)))
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val worker = Executors.newSingleThreadExecutor()
            try {
                val served = worker.submit {
                    server.accept().use { client ->
                        val input = DataInputStream(Channels.newInputStream(client))
                        val output = Channels.newOutputStream(client)
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) headers.append(input.readUnsignedByte().toChar())
                        val key =
                            headers.lines().single { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                        val accept =
                            Base64.getEncoder()
                                .encodeToString(
                                    MessageDigest.getInstance("SHA-1")
                                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                                )
                        output.write(
                            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                                    "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                                .toByteArray()
                        )
                        output.flush()
                        assertEquals(0x81, input.readUnsignedByte())
                        val length = input.readUnsignedByte() and 0x7f
                        val mask = ByteArray(4).also(input::readFully)
                        val request = ByteArray(length).also(input::readFully)
                        request.indices.forEach {
                            request[it] = (request[it].toInt() xor mask[it % 4].toInt()).toByte()
                        }
                        assertEquals("status", request.decodeToString())
                        val bytes = reply.toByteArray()
                        output.write(
                            byteArrayOf(0x81.toByte(), 126, (bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
                        )
                        output.flush()
                    }
                }
                assertEquals(reply, boundedExchange(socket, "status"))
                served.get(2, TimeUnit.SECONDS)
            } finally {
                worker.shutdownNow()
            }
        }
    }
}
