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
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists

internal interface LocalRpcServer : Closeable {
    fun await()
}

internal class UnixDomainSocketRpcServer(
    private val socketPath: Path,
    dispatcher: RpcAnalysisDispatcher,
) : LocalRpcServer {
    private val closed = AtomicBoolean(false)
    private val server = ChannelRpcServer(
        protocolFamily = StandardProtocolFamily.UNIX,
        dispatcher = dispatcher,
        threadNamePrefix = "kast-uds-rpc",
    )

    fun start(): UnixDomainSocketRpcServer {
        Files.createDirectories(checkNotNull(socketPath.parent))
        socketPath.deleteIfExists()
        server.start(UnixDomainSocketAddress.of(socketPath))
        return this
    }

    override fun await() = server.await()

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        server.close()
        socketPath.deleteIfExists()
    }
}

internal class StdioRpcServer(
    private val dispatcher: RpcAnalysisDispatcher,
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
    dispatcher: RpcAnalysisDispatcher,
    reader: BufferedReader,
    writer: BufferedWriter,
) {
    reader.use {
        while (true) {
            val line = it.readLine() ?: break
            if (line.isBlank()) {
                continue
            }
            val response = runBlocking {
                dispatcher.dispatchRaw(line)
            }
            writer.write(response)
            writer.newLine()
            writer.flush()
            if (dispatcher.runAfterResponseActions()) {
                return
            }
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
                    message.contains("Socket closed", ignoreCase = true) ||
                    message.contains("Socket is not connected", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        current = current.cause
    }

    return false
}
