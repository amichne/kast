package io.github.amichne.kast.cli

import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import io.github.amichne.kast.cli.tty.CliFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal interface RuntimeRpcClient {
    fun runtimeStatus(descriptor: ServerInstanceDescriptor): RuntimeStatusResponse

    fun capabilities(descriptor: ServerInstanceDescriptor): BackendCapabilities
}

internal class KastRpcClient(
    private val json: Json,
) : RuntimeRpcClient {
    override fun runtimeStatus(descriptor: ServerInstanceDescriptor): RuntimeStatusResponse =
        get(descriptor = descriptor, method = "runtime/status")

    override fun capabilities(descriptor: ServerInstanceDescriptor): BackendCapabilities =
        get(descriptor = descriptor, method = "capabilities")

    inline fun <reified Response> get(
        descriptor: ServerInstanceDescriptor,
        method: String,
    ): Response = execute(descriptor, method, JsonObject(emptyMap()))

    inline fun <reified Request : Any, reified Response> post(
        descriptor: ServerInstanceDescriptor,
        method: String,
        body: Request,
    ): Response = execute(
        descriptor = descriptor,
        method = method,
        params = json.encodeToJsonElement(body),
    )

    inline fun <reified Response> execute(
        descriptor: ServerInstanceDescriptor,
        method: String,
        params: JsonElement,
    ): Response {
        val response = socketRequest(
            socketPath = Path.of(descriptor.socketPath),
            request = JsonRpcRequest(
                id = JsonPrimitive(1),
                method = method,
                params = params,
            ),
        )

        val error = runCatching {
            json.decodeFromString(JsonRpcErrorResponse.serializer(), response)
        }.getOrNull()
        if (error != null) {
            val apiError = error.error.data
            if (apiError != null) {
                throw CliFailure(
                    code = apiError.code,
                    message = apiError.message,
                    details = apiError.details,
                )
            }
            throw CliFailure(
                code = "RPC_${error.error.code}",
                message = error.error.message,
            )
        }

        val success = runCatching {
            json.decodeFromString(JsonRpcSuccessResponse.serializer(), response)
        }.getOrElse { exception ->
            val apiError = runCatching {
                json.decodeFromString<ApiErrorResponse>(response)
            }.getOrNull()
            if (apiError != null) {
                throw CliFailure(
                    code = apiError.code,
                    message = apiError.message,
                    details = apiError.details,
                )
            }
            throw CliFailure(
                code = "RPC_RESPONSE_INVALID",
                message = exception.message ?: "Invalid JSON-RPC response",
            )
        }
        return json.decodeFromJsonElement(success.result)
    }
}

private fun socketRequest(
    socketPath: Path,
    request: JsonRpcRequest,
): String {
    return runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
            val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name()).buffered()
            writer.write(Json.encodeToString(JsonRpcRequest.serializer(), request))
            writer.newLine()
            writer.flush()
            reader.readLine() ?: throw CliFailure(
                code = "RPC_RESPONSE_MISSING",
                message = "The daemon closed the socket without returning a response",
            )
        }
    }.getOrElse { exception ->
        if (exception is CliFailure) {
            throw exception
        }
        throw CliFailure(
            code = "DAEMON_UNREACHABLE",
            message = exception.message ?: "Failed to reach daemon at $socketPath",
        )
    }
}
