package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.SocketFileIdentity
import io.github.amichne.kast.api.client.SocketOwnerUid
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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists

internal interface LocalRpcServer : Closeable {
    fun await()
}

internal data class BoundSocketEvidence(
    val socketFileIdentity: SocketFileIdentity,
    val socketOwnerUid: SocketOwnerUid,
)

internal class UnixDomainSocketRpcServer(
    private val socketPath: Path,
    private val dispatcher: RpcAnalysisDispatcher,
) : LocalRpcServer {
    private val closed = AtomicBoolean(false)
    private val handlers = Collections.synchronizedSet(mutableSetOf<Thread>())
    private val clients = mutableSetOf<SocketChannel>()
    private val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    @Volatile
    private var boundEvidence: BoundSocketEvidence? = null

    val boundSocketEvidence: BoundSocketEvidence
        get() = checkNotNull(boundEvidence) { "Unix domain socket is not bound" }
    private val acceptThread = thread(
        start = false,
        isDaemon = true,
        name = "kast-uds-rpc-accept",
    ) {
        acceptLoop()
    }

    fun start(): UnixDomainSocketRpcServer {
        try {
            Files.createDirectories(checkNotNull(socketPath.parent))
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath))
            boundEvidence = readBoundSocketEvidence(socketPath)
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

    override fun await() {
        acceptThread.join()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val deadlineNanos = System.nanoTime() + RPC_CLOSE_TIMEOUT_NANOS
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
        if (boundEvidence == readBoundSocketEvidenceOrNull(socketPath)) {
            socketPath.deleteIfExists()
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = runCatching { serverChannel.accept() }.getOrNull() ?: break
            val handler = thread(
                start = false,
                isDaemon = true,
                name = "kast-uds-rpc-client",
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

internal fun readSocketFileIdentity(path: Path): SocketFileIdentity {
    return readBoundSocketEvidence(path).socketFileIdentity
}

internal fun readBoundSocketEvidence(path: Path): BoundSocketEvidence {
    val unix = Files.readAttributes(path, "unix:dev,ino,uid", LinkOption.NOFOLLOW_LINKS)
    return BoundSocketEvidence(
        socketFileIdentity = SocketFileIdentity(
            device = (checkNotNull(unix["dev"]) as Number).toLong(),
            inode = (checkNotNull(unix["ino"]) as Number).toLong(),
        ),
        socketOwnerUid = SocketOwnerUid.of((checkNotNull(unix["uid"]) as Number).toLong()),
    )
}

private fun readBoundSocketEvidenceOrNull(path: Path): BoundSocketEvidence? =
    runCatching { readBoundSocketEvidence(path) }.getOrNull()

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
                dispatcher.dispatchRawForTransport(line)
            }
            writer.write(response.response)
            writer.newLine()
            writer.flush()
            if (response.runAfterFlushAction()) {
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

private const val RPC_CLOSE_TIMEOUT_NANOS = 1_000_000_000L

internal fun joinRpcThreadsUntil(
    threads: Collection<Thread>,
    currentThread: Thread,
    deadlineNanos: Long,
) {
    for (thread in threads) {
        if (thread === currentThread) continue
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) return
        thread.join(
            remainingNanos / 1_000_000,
            (remainingNanos % 1_000_000).toInt(),
        )
    }
}
