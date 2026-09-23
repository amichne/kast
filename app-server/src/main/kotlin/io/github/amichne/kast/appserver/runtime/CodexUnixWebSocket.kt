package io.github.amichne.kast.appserver.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.unixSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal enum class BrokerControlRoute(val path: String) {
    CODEX("/"),
    RUNTIME("/kast-runtime"),
    MANAGEMENT(io.github.amichne.kast.appserver.DaemonManagementProtocol.route),
}

internal suspend fun connectCodexUnixWebSocket(
    socket: Path,
    maximumMessageBytes: Int,
    timeoutMillis: Long,
    route: BrokerControlRoute = BrokerControlRoute.CODEX,
): BrokerUpstreamConnectionAdmission {
    val client =
        HttpClient(CIO) {
            install(WebSockets) { maxFrameSize = maximumMessageBytes.toLong() }
        }
    return try {
        val session =
            withTimeoutOrNull(timeoutMillis) {
                client.webSocketSession {
                    url("ws://localhost${route.path}")
                    unixSocket(socket.toString())
                }
            } ?: return BrokerUpstreamConnectionAdmission.Rejected.also { client.close() }
        BrokerUpstreamConnectionAdmission.Connected(KtorCodexUpstreamConnection(client, session, maximumMessageBytes))
    } catch (cancelled: CancellationException) {
        client.close()
        throw cancelled
    } catch (_: Exception) {
        client.close()
        BrokerUpstreamConnectionAdmission.Rejected
    }
}

private class KtorCodexUpstreamConnection(
    private val client: HttpClient,
    private val session: DefaultClientWebSocketSession,
    private val maximumMessageBytes: Int,
) : BrokerUpstreamConnection {
    private val closed = AtomicBoolean(false)

    override suspend fun send(message: String): BrokerUpstreamSend =
        try {
            if (message.toByteArray(Charsets.UTF_8).size > maximumMessageBytes) {
                BrokerUpstreamSend.REJECTED
            } else {
                session.send(message)
                BrokerUpstreamSend.SENT
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BrokerUpstreamSend.REJECTED
        }

    override suspend fun receive(): BrokerUpstreamFrame =
        try {
            when (val frame = session.incoming.receiveCatching().getOrNull()) {
                is Frame.Text -> {
                    val message = frame.readText()
                    if (message.toByteArray(Charsets.UTF_8).size <= maximumMessageBytes) {
                        BrokerUpstreamFrame.Text(message)
                    } else {
                        BrokerUpstreamFrame.Rejected
                    }
                }
                null,
                is Frame.Close -> BrokerUpstreamFrame.Closed
                else -> BrokerUpstreamFrame.Rejected
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BrokerUpstreamFrame.Rejected
        }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            session.close()
        } finally {
            client.close()
        }
    }
}

