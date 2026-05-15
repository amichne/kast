package io.github.amichne.kast.server

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists

internal interface LocalRpcServer : Closeable {
    fun await()
}

internal class UnixDomainSocketRpcServer(
    private val socketPath: Path,
    private val dispatcher: AnalysisDispatcher,
) : LocalRpcServer {
    private val closed = AtomicBoolean(false)
    private val handlers = Collections.synchronizedList(mutableListOf<Thread>())
    private val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val acceptThread = thread(
        start = false,
        isDaemon = true,
        name = "kast-uds-rpc-accept",
    ) {
        acceptLoop()
    }

    fun start(): UnixDomainSocketRpcServer {
        Files.createDirectories(checkNotNull(socketPath.parent))
        socketPath.deleteIfExists()
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath))
        acceptThread.start()
        return this
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
        socketPath.deleteIfExists()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = runCatching { serverChannel.accept() }.getOrNull() ?: break
            val handler = thread(
                start = true,
                isDaemon = true,
                name = "kast-uds-rpc-client",
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

internal class StdioRpcServer(
    private val dispatcher: AnalysisDispatcher,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
) : LocalRpcServer {
    private val thread = thread(
        start = false,
        isDaemon = true,
        name = "kast-stdio-rpc",
    ) {
        processStream(
            reader = input.reader(StandardCharsets.UTF_8).buffered(),
            writer = OutputStreamWriter(output, StandardCharsets.UTF_8).buffered(),
        )
    }

    fun start(): StdioRpcServer {
        thread.start()
        return this
    }

    override fun await() {
        thread.join()
    }

    override fun close() {
        runCatching { output.flush() }
    }

    private fun processStream(
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        processRpcStream(dispatcher, reader, writer)
    }
}

internal fun processRpcStream(
    dispatcher: AnalysisDispatcher,
    reader: BufferedReader,
    writer: BufferedWriter,
) {
    reader.useLines { lines ->
        lines.forEach { line ->
            if (line.isBlank()) {
                return@forEach
            }
            val response = runBlocking {
                dispatcher.dispatchRaw(line)
            }
            writer.write(response)
            writer.newLine()
            writer.flush()
        }
    }
}

internal fun isExpectedClientDisconnect(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is ClosedChannelException,
            is AsynchronousCloseException,
            -> return true

            is IOException -> {
                val message = current.message.orEmpty()
                if (
                    message.contains("Broken pipe", ignoreCase = true) ||
                    message.contains("Connection reset", ignoreCase = true) ||
                    message.contains("Socket closed", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        current = current.cause
    }

    return false
}
