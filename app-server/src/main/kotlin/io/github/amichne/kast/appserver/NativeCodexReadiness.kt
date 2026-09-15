package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.connectCodexUnixWebSocket
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal enum class NativeCodexReadiness {
    READY,
    REJECTED;

    companion object {
        /** Native initialize traverses the broker and private upstream; it creates no thread. */
        suspend fun observe(path: Path, timeoutMillis: Long): NativeCodexReadiness =
            withTimeoutOrNull(timeoutMillis) {
                val connection = when (val connected = connectCodexUnixWebSocket(path, 65_536, timeoutMillis)) {
                    is BrokerUpstreamConnectionAdmission.Connected -> connected.connection
                    BrokerUpstreamConnectionAdmission.Rejected -> return@withTimeoutOrNull REJECTED
                }
                try {
                    exchange(connection)
                } finally {
                    connection.close()
                }
            } ?: REJECTED

        internal suspend fun exchange(connection: BrokerUpstreamConnection): NativeCodexReadiness = try {
            if (connection.send(json.encodeToString(NativeInitialize.serializer(), NativeInitialize())) != BrokerUpstreamSend.SENT) REJECTED
            else {
                val frame = connection.receive()
                if (frame !is BrokerUpstreamFrame.Text) REJECTED
                else {
                    val reply = json.decodeFromString(NativeInitializeReply.serializer(), frame.message)
                    if (reply.id != REQUEST_ID || reply.error != null || reply.result?.userAgent.isNullOrBlank()) REJECTED
                    else if (connection.send(json.encodeToString(NativeInitialized.serializer(), NativeInitialized())) == BrokerUpstreamSend.SENT) READY
                    else REJECTED
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            REJECTED
        }

        private const val REQUEST_ID = "kast-protocol-readiness"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        @Serializable private data class NativeInitialize(
            val id: String = REQUEST_ID,
            val method: String = "initialize",
            val params: NativeInitializeParams = NativeInitializeParams(),
        )
        @Serializable private data class NativeInitializeParams(val clientInfo: NativeClientInfo = NativeClientInfo())
        @Serializable private data class NativeClientInfo(val name: String = "kast-readiness", val version: String = "1")
        @Serializable private data class NativeInitialized(val method: String = "initialized")
        @Serializable private data class NativeInitializeReply(
            val id: String,
            val result: NativeInitializeResult? = null,
            val error: NativeError? = null,
        )
        @Serializable private data class NativeInitializeResult(val userAgent: String)
        @Serializable private data class NativeError(
            val code: Int,
            val message: String,
            // JSON-RPC error data is a protocol-defined opaque field.
            val data: JsonElement? = null,
        )
    }
}
