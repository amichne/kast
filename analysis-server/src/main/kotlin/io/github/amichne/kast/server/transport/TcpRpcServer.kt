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
    private val dispatcher: RpcAnalysisDispatcher,
) : LocalRpcServer {
    private val closed = AtomicBoolean(false)
    private val handlers = Collections.synchronizedSet(mutableSetOf<Thread>())
    private val clients = mutableSetOf<SocketChannel>()
    private val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.INET)
    private val acceptThread = thread(
        start = false,
        isDaemon = true,
        name = "kast-tcp-rpc-accept",
    ) {
        acceptLoop()
    }

    fun start(): TcpRpcServer {
        try {
            serverChannel.bind(InetSocketAddress(host, port))
            acceptThread.start()
            return this
        } catch (startupFailure: Throwable) {
            try {
                close()
            } catch (cleanupFailure: Throwable) {
                startupFailure.addSuppressed(cleanupFailure)
            }
            throw startupFailure
        }
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
        val deadlineNanos = System.nanoTime() + 1_000_000_000L
        runCatching { serverChannel.close() }
        val currentThread = Thread.currentThread()
        val (acceptedClients, activeHandlers) = synchronized(handlers) {
            clients.toList() to handlers.toList()
        }
        acceptedClients.forEach { client ->
            runCatching { client.close() }
        }
        activeHandlers.forEach { handler ->
            if (handler !== currentThread) {
                handler.interrupt()
            }
        }
        joinRpcThreadsUntil(
            threads = listOf(acceptThread) + activeHandlers,
            currentThread = currentThread,
            deadlineNanos = deadlineNanos,
        )
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = runCatching { serverChannel.accept() }.getOrNull() ?: break
            val handler = thread(
                start = false,
                isDaemon = true,
                name = "kast-tcp-rpc-client",
            ) {
                try {
                    client.use(::handleClient)
                } finally {
                    synchronized(handlers) {
                        clients.remove(client)
                        handlers.remove(Thread.currentThread())
                    }
                }
            }
            val started = synchronized(handlers) {
                if (closed.get()) {
                    false
                } else {
                    clients += client
                    handlers += handler
                    handler.start()
                    true
                }
            }
            if (!started) {
                runCatching { client.close() }
                    .onFailure { throw it }
                break
            }
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
