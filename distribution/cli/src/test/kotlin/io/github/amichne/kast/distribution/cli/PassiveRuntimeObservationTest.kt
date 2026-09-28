package io.github.amichne.kast.distribution.cli

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class TestRuntimeReply(val type: String, val value: String)

class PassiveRuntimeObservationTest {
    @TempDir lateinit var temporary: Path

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
