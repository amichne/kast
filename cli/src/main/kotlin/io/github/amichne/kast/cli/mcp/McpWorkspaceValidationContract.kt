package io.github.amichne.kast.cli.mcp

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
)

@Serializable
internal enum class McpValidationKind(val lookupKind: String, val discoveryKind: String, val inspectedKind: String) {
    @SerialName("class") CLASS("class", "class", "classlike"),
    @SerialName("function") FUNCTION("symbol", "function", "function"),
    @SerialName("property") PROPERTY("symbol", "property", "property"),
    @SerialName("type_alias") TYPE_ALIAS("symbol", "type-alias", "type-alias"),
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

@Serializable internal data class McpValidationEndpoint(val file: String, val name: String)

internal fun McpValidationRequest.valid(root: Path): Boolean =
    listOfNotNull(declaration?.name, relation?.source?.name, relation?.target?.name).all { it.isNotBlank() } &&
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
    val discovery: McpProbe,
    val exactInspection: McpProbe,
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

/** Exact input schema for the three optional probes. Missing probes remain unverified in output. */
internal fun validationInputSchema(): JsonElement = validationSchemaJson.encodeToJsonElement(McpValidationInputSchema())

@Serializable
private data class McpValidationInputSchema(
    val type: String = "object",
    val properties: McpValidationProperties = McpValidationProperties(),
    val additionalProperties: Boolean = false,
)

@Serializable
private data class McpValidationProperties(
    val declaration: McpValidationDeclarationSchema = McpValidationDeclarationSchema(),
    val relation: McpValidationRelationSchema = McpValidationRelationSchema(),
    val diagnosticPath: McpValidationStringSchema = McpValidationStringSchema(),
)

@Serializable
private data class McpValidationDeclarationSchema(
    val type: String = "object",
    val properties: McpValidationDeclarationProperties = McpValidationDeclarationProperties(),
    val required: List<String> = listOf("kind", "name", "file"),
    val additionalProperties: Boolean = false,
)

@Serializable
private data class McpValidationDeclarationProperties(
    val kind: McpValidationStringSchema =
        McpValidationStringSchema(options = listOf("class", "function", "property", "type_alias")),
    val name: McpValidationStringSchema = McpValidationStringSchema(),
    val file: McpValidationStringSchema = McpValidationStringSchema(),
)

@Serializable
private data class McpValidationRelationSchema(
    val type: String = "object",
    val properties: McpValidationRelationProperties = McpValidationRelationProperties(),
    val required: List<String> = listOf("kind", "source", "target"),
    val additionalProperties: Boolean = false,
)

@Serializable
private data class McpValidationRelationProperties(
    val kind: McpValidationStringSchema =
        McpValidationStringSchema(
            options =
                listOf("references", "callers", "callees", "implementations", "inheritors", "overrides", "type_uses")
        ),
    val source: McpValidationEndpointSchema = McpValidationEndpointSchema(),
    val target: McpValidationEndpointSchema = McpValidationEndpointSchema(),
)

@Serializable
private data class McpValidationEndpointSchema(
    val type: String = "object",
    val properties: McpValidationEndpointProperties = McpValidationEndpointProperties(),
    val required: List<String> = listOf("file", "name"),
    val additionalProperties: Boolean = false,
)

@Serializable
private data class McpValidationEndpointProperties(
    val file: McpValidationStringSchema = McpValidationStringSchema(),
    val name: McpValidationStringSchema = McpValidationStringSchema(),
)

@Serializable
private data class McpValidationStringSchema(
    val type: String = "string",
    @SerialName("enum") val options: List<String>? = null,
    val minLength: Int = 1,
)

private val validationSchemaJson = Json {
    encodeDefaults = true
    explicitNulls = false
}
