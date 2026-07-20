package io.github.amichne.kast.server

import java.io.Closeable
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class ChannelRpcServer(
    protocolFamily: StandardProtocolFamily,
    private val dispatcher: RpcAnalysisDispatcher,
    threadNamePrefix: String,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val handlers = Collections.synchronizedList(mutableListOf<Thread>())
    private val serverChannel = ServerSocketChannel.open(protocolFamily)
    private val acceptThread = thread(
        start = false,
        isDaemon = true,
        name = "$threadNamePrefix-accept",
    ) {
        acceptLoop(threadNamePrefix)
    }

    val localAddress: SocketAddress
        get() = serverChannel.localAddress

    fun start(address: SocketAddress) {
        serverChannel.bind(address)
        acceptThread.start()
    }

    fun await() {
        acceptThread.join()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching { serverChannel.close() }
        val currentThread = Thread.currentThread()
        handlers.toList().forEach { handler ->
            if (handler != currentThread) {
                handler.join(1_000)
            }
        }
    }

    private fun acceptLoop(threadNamePrefix: String) {
        while (!closed.get()) {
            val client = runCatching { serverChannel.accept() }.getOrNull() ?: break
            val handler = thread(
                start = true,
                isDaemon = true,
                name = "$threadNamePrefix-client",
            ) {
                client.use(::handleClient)
            }
            handlers += handler
        }
    }

    private fun handleClient(channel: SocketChannel) {
        val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name())
        val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name())
        runCatching {
            processRpcStream(
                dispatcher = dispatcher,
                reader = reader.buffered(),
                writer = writer.buffered(),
            )
        }.getOrElse { error ->
            if (!isExpectedClientDisconnect(error)) {
                throw error
            }
        }
    }
}
