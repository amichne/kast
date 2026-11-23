@file:Suppress("MagicNumber") // WebSocket frame constants.

package io.github.amichne.kast.distribution.cli

import java.io.DataInputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val EXCHANGE_DEADLINE_MILLIS = 1500L
private const val MAXIMUM_FRAME_BYTES = 16384
private const val MAXIMUM_HEADER_BYTES = 8192
private const val WEBSOCKET_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

internal fun boundedExchange(socket: Path, request: String): String? {
    val channel = AtomicReference<SocketChannel?>()
    val executor = Executors.newSingleThreadExecutor()
    return try {
        val result =
            executor.submit<String?> {
                if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) return@submit null
                SocketChannel.open(StandardProtocolFamily.UNIX).use {
                    channel.set(it)
                    it.connect(UnixDomainSocketAddress.of(socket))
                    websocketExchange(it, request)
                }
            }
        try {
            result.get(EXCHANGE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            result.cancel(true)
            null
        }
    } finally {
        try {
            channel.get()?.close()
        } catch (_: Exception) {
            /* deadline closes the owned socket */
        }
        executor.shutdownNow()
    }
}

private fun websocketExchange(channel: SocketChannel, request: String): String? {
    val input = DataInputStream(Channels.newInputStream(channel))
    val output = Channels.newOutputStream(channel)
    val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
    val key = Base64.getEncoder().encodeToString(keyBytes)
    val handshake =
        "GET /kast-management HTTP/1.1\r\nHost: localhost\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Key: $key\r\n\r\n"
    output.write(handshake.toByteArray())
    output.flush()
    val header = readHeader(input) ?: return null
    val accept =
        Base64.getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_ACCEPT_GUID).toByteArray()))
    if (!header.startsWith("HTTP/1.1 101") || !header.contains("Sec-WebSocket-Accept: $accept", ignoreCase = true))
        return null
    val payload = request.toByteArray()
    if (payload.size > MAXIMUM_FRAME_BYTES) return null
    val mask = ByteArray(4).also(SecureRandom()::nextBytes)
    val frame = ByteBuffer.allocate(payload.size + 8)
    frame.put(0x81.toByte())
    if (payload.size < 126) frame.put((0x80 or payload.size).toByte())
    else {
        frame.put(0xfe.toByte())
        frame.putShort(payload.size.toShort())
    }
    frame.put(mask)
    payload.forEachIndexed { index, byte -> frame.put((byte.toInt() xor mask[index % 4].toInt()).toByte()) }
    output.write(frame.array(), 0, frame.position())
    output.flush()
    val first = input.readUnsignedByte()
    if (first != 0x81) return null
    val second = input.readUnsignedByte()
    if (second and 0x80 != 0) return null
    val size =
        when (second) {
            in 0..125 -> second
            126 -> input.readUnsignedShort()
            else -> return null
        }
    if (size > MAXIMUM_FRAME_BYTES) return null
    return ByteArray(size).also(input::readFully).decodeToString()
}

private fun readHeader(input: DataInputStream): String? {
    val bytes = ArrayList<Byte>()
    while (bytes.size < MAXIMUM_HEADER_BYTES) {
        bytes += input.readByte()
        if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte()))
            return bytes.toByteArray().decodeToString()
    }
    return null
}
