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
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists

internal interface LocalRpcServer : Closeable {
    fun await()
}

internal data class BoundSocketEvidence(
    val socketFileIdentity: SocketFileIdentity,
    val socketOwnerUid: SocketOwnerUid,
)

private sealed interface UnixSocketHandlerLifecycle {
    data object Accepting : UnixSocketHandlerLifecycle

    data object Closed : UnixSocketHandlerLifecycle
}

private sealed interface UnixSocketHandlerAdmission {
    data object Accepted : UnixSocketHandlerAdmission

    data object RejectedAfterClose : UnixSocketHandlerAdmission
}

private sealed interface UnixSocketHandlerShutdown {
    data class Started(
        val acceptedClients: List<SocketChannel>,
        val activeHandlers: List<Thread>,
    ) : UnixSocketHandlerShutdown

    data object AlreadyClosed : UnixSocketHandlerShutdown
}

internal class UnixDomainSocketRpcServer(
    private val socketPath: Path,
    private val dispatcher: RpcAnalysisDispatcher,
) : LocalRpcServer {
    private val handlerLifecycleLock = Any()
    private var handlerLifecycle: UnixSocketHandlerLifecycle = UnixSocketHandlerLifecycle.Accepting
    private val handlers = ConcurrentHashMap.newKeySet<Thread>()
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()
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
        when (val shutdown = beginHandlerShutdown()) {
            UnixSocketHandlerShutdown.AlreadyClosed -> return
            is UnixSocketHandlerShutdown.Started -> {
                val deadlineNanos = System.nanoTime() + RPC_CLOSE_TIMEOUT_NANOS
                runCatching { serverChannel.close() }
                val currentThread = Thread.currentThread()
                shutdown.acceptedClients.forEach { client ->
                    runCatching { client.close() }
                }
                shutdown.activeHandlers.forEach { handler ->
                    if (handler !== currentThread) {
                        handler.interrupt()
                    }
                }
                joinRpcThreadsUntil(
                    threads = listOf(acceptThread) + shutdown.activeHandlers,
                    currentThread = currentThread,
                    deadlineNanos = deadlineNanos,
                )
                if (boundEvidence == readBoundSocketEvidenceOrNull(socketPath)) {
                    socketPath.deleteIfExists()
                }
            }
        }
    }

    private fun acceptLoop() {
        while (true) {
            val client = runCatching { serverChannel.accept() }.getOrNull() ?: break
            val handler = thread(
                start = false,
                isDaemon = true,
                name = "kast-uds-rpc-client",
            ) {
                try {
                    client.use(::handleClient)
                } finally {
                    clients.remove(client)
                    handlers.remove(Thread.currentThread())
                }
            }
            when (admitHandler(client, handler)) {
                UnixSocketHandlerAdmission.Accepted -> Unit
                UnixSocketHandlerAdmission.RejectedAfterClose -> {
                    runCatching { client.close() }
                        .onFailure { throw it }
                    break
                }
            }
        }
    }

    /**
     * Proof transition: `(SocketChannel, Thread) -> UnixSocketHandlerAdmission`.
     *
     * [UnixSocketHandlerAdmission.Accepted] establishes that both resources are owned by this server,
     * registered for shutdown, and that the handler has started. The closed expected failure is
     * [UnixSocketHandlerAdmission.RejectedAfterClose]. Raw resource extraction remains confined to the
     * socket accept boundary, where rejected clients are closed.
     */
    private fun admitHandler(
        client: SocketChannel,
        handler: Thread,
    ): UnixSocketHandlerAdmission = synchronized(handlerLifecycleLock) {
        when (handlerLifecycle) {
            UnixSocketHandlerLifecycle.Accepting -> {
                clients += client
                handlers += handler
                handler.start()
                UnixSocketHandlerAdmission.Accepted
            }

            UnixSocketHandlerLifecycle.Closed -> UnixSocketHandlerAdmission.RejectedAfterClose
        }
    }

    /**
     * Proof transition: `UnixSocketHandlerLifecycle -> UnixSocketHandlerShutdown`.
     *
     * [UnixSocketHandlerShutdown.Started] establishes closed admission and carries the complete owned-resource
     * snapshot that existed at the transition. [UnixSocketHandlerShutdown.AlreadyClosed] is the closed expected
     * idempotency outcome. Raw client and thread extraction is permitted only at the transport close boundary.
     */
    private fun beginHandlerShutdown(): UnixSocketHandlerShutdown = synchronized(handlerLifecycleLock) {
        when (handlerLifecycle) {
            UnixSocketHandlerLifecycle.Accepting -> {
                handlerLifecycle = UnixSocketHandlerLifecycle.Closed
                UnixSocketHandlerShutdown.Started(
                    acceptedClients = clients.toList(),
                    activeHandlers = handlers.toList(),
                )
            }

            UnixSocketHandlerLifecycle.Closed -> UnixSocketHandlerShutdown.AlreadyClosed
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
                withRpcTraceCorrelation(line, dispatcher::dispatchRawForTransport)
            }
            writer.write(response.response)
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
