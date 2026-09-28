package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.generatedRequestSchema
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Typed probes and output states for the read-only workspace validation call. */
@Serializable
internal data class McpValidationRequest(
    val declaration: McpValidationDeclaration? = null,
    val relation: McpValidationRelation? = null,
    val diagnosticPath: String? = null,
)

@Serializable
internal data class McpValidationDeclaration(
    val kind: McpValidationKind,
    val name: String,
    val file: String,
    val sourceSetName: String? = null,
)

@Serializable
internal enum class McpValidationKind(val queryKind: String, val queryResultKind: String) {
    @SerialName("class") CLASS("CLASS", "classlike"),
    @SerialName("function") FUNCTION("FUNCTION", "function"),
    @SerialName("property") PROPERTY("PROPERTY", "property"),
    @SerialName("type_alias") TYPE_ALIAS("TYPE_ALIAS", "type-alias"),
}

@Serializable
internal data class McpValidationRelation(
    val kind: McpValidationRelationKind,
    val source: McpValidationEndpoint,
    val target: McpValidationEndpoint,
)

@Serializable
internal enum class McpValidationRelationKind {
    @SerialName("references") REFERENCES,
    @SerialName("callers") CALLERS,
    @SerialName("callees") CALLEES,
    @SerialName("implementations") IMPLEMENTATIONS,
    @SerialName("inheritors") INHERITORS,
    @SerialName("overrides") OVERRIDES,
    @SerialName("type_uses") TYPE_USES,
}

@Serializable
internal data class McpValidationEndpoint(val file: String, val name: String, val sourceSetName: String? = null)

internal fun McpValidationRequest.valid(root: Path): Boolean =
    listOfNotNull(declaration?.name, relation?.source?.name, relation?.target?.name).all { it.isNotBlank() } &&
        listOfNotNull(declaration?.sourceSetName, relation?.source?.sourceSetName, relation?.target?.sourceSetName)
            .all { it.isNotBlank() } &&
        listOfNotNull(declaration?.file, relation?.source?.file, relation?.target?.file, diagnosticPath).all {
            root.resolveProbePath(it) != null
        }

internal fun Path.resolveProbePath(raw: String): Path? {
    if (raw.isBlank()) return null
    return try {
        val candidate = Path.of(raw)
        val resolved = (if (candidate.isAbsolute) candidate else resolve(candidate)).normalize()
        resolved.takeIf { it.startsWith(this.normalize()) }
    } catch (_: InvalidPathException) {
        null
    }
}

@Serializable
internal data class McpValidationResult(
    val status: McpValidationStatus = McpValidationStatus.COMPLETE,
    val data: McpValidationData,
)

@Serializable
internal data class McpValidationData(
    val declarationQuery: McpProbe,
    val sourceRead: McpProbe,
    val relation: McpProbe,
    val diagnostics: McpProbe,
)

@Serializable
internal data class McpProbe(val status: McpProbeStatus, val message: String, val evidence: JsonElement? = null) {
    companion object {
        fun passed(message: String, evidence: JsonElement? = null) = McpProbe(McpProbeStatus.PASSED, message, evidence)

        fun failed(message: String, evidence: JsonElement? = null) = McpProbe(McpProbeStatus.FAILED, message, evidence)

        fun unverified(message: String, evidence: JsonElement? = null) =
            McpProbe(McpProbeStatus.UNVERIFIED, message, evidence)
    }
}

@Serializable
internal enum class McpProbeStatus {
    @SerialName("passed") PASSED,
    @SerialName("failed") FAILED,
    @SerialName("unverified") UNVERIFIED,
}

@Serializable
internal data class McpValidationRejected(
    val status: McpValidationStatus = McpValidationStatus.REJECTED,
    val error: McpValidationError,
)

@Serializable internal data class McpValidationError(val code: McpValidationErrorCode, val message: String)

@Serializable
internal enum class McpValidationErrorCode {
    INVALID_REQUEST
}

@Serializable
internal enum class McpValidationStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("rejected") REJECTED,
}

/** Project the typed probe request without maintaining a second field inventory. */
internal fun validationInputSchema(): JsonElement = generatedRequestSchema(McpValidationRequest.serializer())
