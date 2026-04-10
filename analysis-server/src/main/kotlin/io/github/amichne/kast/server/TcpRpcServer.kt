package io.github.amichne.kast.server

import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

internal class TcpRpcServer(
    private val host: String,
    private val port: Int,
    private val dispatcher: AnalysisDispatcher,
) : LocalRpcServer {
    private val closed = AtomicBoolean(false)
    private val handlers = Collections.synchronizedList(mutableListOf<Thread>())
    private val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.INET)
    private val acceptThread = thread(
        start = false,
        isDaemon = true,
        name = "kast-tcp-rpc-accept",
    ) {
        acceptLoop()
    }

    fun start(): TcpRpcServer {
        serverChannel.bind(InetSocketAddress(host, port))
        acceptThread.start()
        return this
    }

    fun boundPort(): Int {
        val address = serverChannel.localAddress as InetSocketAddress
        return address.port
    }

    override fun await() {
        acceptThread.join()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        runCatching { serverChannel.close() }
        handlers.toList().forEach { handler ->
            handler.join(1_000)
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = runCatching { serverChannel.accept() }.getOrNull() ?: break
            val handler = thread(
                start = true,
                isDaemon = true,
                name = "kast-tcp-rpc-client",
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
