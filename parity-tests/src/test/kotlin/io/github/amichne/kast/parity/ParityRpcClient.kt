package io.github.amichne.kast.parity

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.protocol.JsonRpcErrorResponse
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC client for parity testing. Connects to a backend via Unix domain socket
 * and exposes typed wrappers for every AnalysisBackend method.
 */
class ParityRpcClient(
    private val socketPath: Path,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val requestId = AtomicLong(1)

    fun capabilities(): BackendCapabilities = get("capabilities")
    fun runtimeStatus(): RuntimeStatusResponse = get("runtime/status")
    fun health(): HealthResponse = get("health")
    fun resolveSymbol(query: SymbolQuery): SymbolResult = post("symbol/resolve", query)
    fun findReferences(query: ReferencesQuery): ReferencesResult = post("references", query)
    fun callHierarchy(query: CallHierarchyQuery): CallHierarchyResult = post("call-hierarchy", query)
    fun typeHierarchy(query: TypeHierarchyQuery): TypeHierarchyResult = post("type-hierarchy", query)
    fun semanticInsertionPoint(query: SemanticInsertionQuery): SemanticInsertionResult = post("semantic-insertion-point", query)
    fun diagnostics(query: DiagnosticsQuery): DiagnosticsResult = post("diagnostics", query)
    fun rename(query: RenameQuery): RenameResult = post("rename", query)
    fun applyEdits(query: ApplyEditsQuery): ApplyEditsResult = post("edits/apply", query)
    fun optimizeImports(query: ImportOptimizeQuery): ImportOptimizeResult = post("imports/optimize", query)
    fun refresh(query: RefreshQuery): RefreshResult = post("workspace/refresh", query)

    /** Raw JSON-RPC call returning the result [JsonElement]. */
    fun rawCall(method: String, params: JsonElement = JsonNull): JsonElement {
        val request = JsonRpcRequest(
            id = JsonPrimitive(requestId.getAndIncrement()),
            method = method,
            params = params,
        )
        val responseLine = socketRequest(request)
        val error = runCatching { json.decodeFromString(JsonRpcErrorResponse.serializer(), responseLine) }.getOrNull()
        if (error != null) {
            throw ParityRpcException(
                method = method,
                code = error.error.code,
                message = error.error.message,
            )
        }
        val success = json.decodeFromString(JsonRpcSuccessResponse.serializer(), responseLine)
        return success.result
    }

    private inline fun <reified R> get(method: String): R {
        val result = rawCall(method)
        return json.decodeFromJsonElement(result)
    }

    private inline fun <reified Q : Any, reified R> post(method: String, query: Q): R {
        val params = json.encodeToJsonElement(query)
        val result = rawCall(method, params)
        return json.decodeFromJsonElement(result)
    }

    private fun socketRequest(request: JsonRpcRequest): String {
        return SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val writer = Channels.newWriter(channel, StandardCharsets.UTF_8.name()).buffered()
            val reader = Channels.newReader(channel, StandardCharsets.UTF_8.name()).buffered()
            writer.write(Json.encodeToString(JsonRpcRequest.serializer(), request))
            writer.newLine()
            writer.flush()
            reader.readLine() ?: throw ParityRpcException(
                method = request.method,
                code = -1,
                message = "No response from server",
            )
        }
    }
}

class ParityRpcException(
    val method: String,
    val code: Int,
    override val message: String,
) : RuntimeException("JSON-RPC error on '$method' (code=$code): $message")
