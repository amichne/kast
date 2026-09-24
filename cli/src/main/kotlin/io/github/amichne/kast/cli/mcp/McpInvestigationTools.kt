package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.ide.ExistingIdeCliCapabilities
import io.github.amichne.kast.cli.ide.executeExistingIdeCli
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/** Session tools compose existing read capabilities; they are not compiler operations. */
internal data class McpSupplementalTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val invoke: (JsonObject) -> CliExit,
)

internal class McpInvestigationTools(
    private val directory: Path,
    private val capabilities: ExistingIdeCliCapabilities,
    private val expectedOperations: Set<String>,
    private val invokeRead: (String, JsonObject) -> CliExit,
) {
    val tools: List<McpSupplementalTool> =
        listOf(
            McpSupplementalTool(
                name = "health_check",
                description =
                    "Observe exact workspace binding, saved/indexed readiness, and supported host operations. " +
                        "This passive check does not validate semantic answers.",
                inputSchema = investigationJson.encodeToJsonElement(McpEmptyInputSchema()),
                invoke = ::health,
            ),
            McpSupplementalTool(
                name = "validate_workspace",
                description =
                    "Run explicit read-only declaration, exact-symbol, source, relation, and IDE diagnostic " +
                        "probes. Each probe reports passed, failed, or unverified independently.",
                inputSchema = validationInputSchema(),
                invoke = { arguments -> validateWorkspace(arguments, directory, invokeRead) },
            ),
        )

    private fun health(arguments: JsonObject): CliExit {
        if (arguments.isNotEmpty())
            return healthRejected(McpHealthErrorCode.INVALID_REQUEST, "No arguments are accepted")
        val exit =
            executeExistingIdeCli(
                argv = listOf("ide", "status"),
                start = directory,
                capabilities = capabilities,
            )
        if (exit !is CliExit.Complete)
            return healthRejected(McpHealthErrorCode.HOST_UNAVAILABLE, "The existing IDE host is unavailable")
        val observed =
            try {
                investigationJson.decodeFromString<McpNativeStatus>(exit.document.value)
            } catch (_: SerializationException) {
                return healthRejected(McpHealthErrorCode.INTERNAL_ERROR, "The IDE status response was invalid")
            }
        if (observed.root != directory.toRealPath().toString() || observed.type != "KAST_IDE_HOST")
            return healthRejected(McpHealthErrorCode.OUT_OF_SCOPE, "The IDE host is bound to another workspace")
        val indexed = observed.readiness.status == McpNativeReadiness.ADMISSION_READY
        val data =
            McpHealthData(
                workspaceBinding = observed.root,
                host = observed.host,
                readiness = if (indexed) McpHealthReadiness.READY else McpHealthReadiness.UNAVAILABLE,
                contentView = if (indexed) McpHealthContentView.SAVED_PSI_COMMITTED else null,
                hostState = if (indexed) McpHealthHostState.INDEXED else McpHealthHostState.UNAVAILABLE,
                supportedCapabilities = observed.operations.sorted(),
                unavailableCapabilities = (expectedOperations - observed.operations.toSet()).sorted(),
                readinessEvidence = observed.readiness.rejection,
            )
        return CliExit.Complete(healthReadyFactory.create(McpHealthReady(data = data)))
    }

    private fun healthRejected(code: McpHealthErrorCode, message: String): CliExit.OperationRejected =
        CliExit.OperationRejected(
            healthRejectedFactory.create(McpHealthRejected(error = McpHealthError(code, message)))
        )
}

@Serializable
private data class McpEmptyInputSchema(
    val type: String = "object",
    val properties: McpEmptyProperties = McpEmptyProperties(),
    val additionalProperties: Boolean = false,
)

@Serializable private class McpEmptyProperties

@Serializable
private data class McpNativeStatus(
    val root: String,
    val host: String,
    val type: String,
    val operations: List<String>,
    val readiness: McpNativeReadinessDocument,
)

@Serializable
private data class McpNativeReadinessDocument(
    val status: McpNativeReadiness,
    /** Host-owned refusal is opaque evidence. Never interpret it as a semantic result. */
    val rejection: JsonElement? = null,
)

@Serializable
private enum class McpNativeReadiness {
    @SerialName("admission_ready") ADMISSION_READY,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
private data class McpHealthReady(
    val status: McpHealthStatus = McpHealthStatus.COMPLETE,
    val data: McpHealthData,
)

@Serializable
private data class McpHealthData(
    val workspaceBinding: String,
    val host: String,
    val readiness: McpHealthReadiness,
    val contentView: McpHealthContentView?,
    val hostState: McpHealthHostState,
    val supportedCapabilities: List<String>,
    val unavailableCapabilities: List<String>,
    /** Only the native readiness refusal is dynamic; the surrounding contract is typed. */
    val readinessEvidence: JsonElement?,
)

@Serializable
private enum class McpHealthReadiness {
    READY,
    UNAVAILABLE,
}

@Serializable
private enum class McpHealthHostState {
    INDEXED,
    UNAVAILABLE,
}

@Serializable
private enum class McpHealthContentView {
    SAVED_PSI_COMMITTED
}

@Serializable
private enum class McpHealthStatus {
    @SerialName("complete") COMPLETE
}

@Serializable
private data class McpHealthRejected(
    val status: McpHealthRejectedStatus = McpHealthRejectedStatus.REJECTED,
    val error: McpHealthError,
)

@Serializable
private enum class McpHealthRejectedStatus {
    @SerialName("rejected") REJECTED
}

@Serializable private data class McpHealthError(val code: McpHealthErrorCode, val message: String)

@Serializable
private enum class McpHealthErrorCode {
    INVALID_REQUEST,
    HOST_UNAVAILABLE,
    OUT_OF_SCOPE,
    INTERNAL_ERROR,
}

private val investigationJson = Json { ignoreUnknownKeys = true }
private val healthReadyFactory = CanonicalJsonDocument.generated(McpHealthReady.serializer())
private val healthRejectedFactory = CanonicalJsonDocument.generated(McpHealthRejected.serializer())
