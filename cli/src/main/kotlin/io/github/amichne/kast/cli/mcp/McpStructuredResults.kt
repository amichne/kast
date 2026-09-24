package io.github.amichne.kast.cli.mcp

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The canonical payload under `data` is intentionally opaque; its owner retains the full operation contract. */
internal object McpStructuredResults {
    private val wire = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    val readSchema: JsonObject =
        wire
            .encodeToJsonElement(
                McpOutputSchema(
                    oneOf =
                        listOf(
                            variant("complete", "data", "coverage", "basis"),
                            variant("partial", "data", "coverage", "basis", "stopReason"),
                            variant("rejected", "error"),
                            variant("unavailable", "error"),
                        )
                )
            )
            .jsonObject

    val investigationSchema: JsonObject =
        wire
            .encodeToJsonElement(
                McpOutputSchema(
                    oneOf =
                        listOf(
                            variant("complete", "data"),
                            variant("rejected", "error"),
                        )
                )
            )
            .jsonObject

    val refreshSchema: JsonObject =
        wire
            .encodeToJsonElement(
                McpOutputSchema(
                    oneOf =
                        listOf(variant("complete", "data"), variant("partial", "data"), variant("rejected", "error"))
                )
            )
            .jsonObject

    val changeSchema: JsonObject =
        wire
            .encodeToJsonElement(
                McpOutputSchema(
                    oneOf =
                        listOf(
                            variant("complete", "planIdentity", "plan", "application"),
                            variant("qualified", "planIdentity", "plan", "issue"),
                            variant("rejected", "error"),
                        )
                )
            )
            .jsonObject

    val genericSchema: JsonObject =
        wire
            .encodeToJsonElement(McpStatusSchema(properties = mapOf("status" to McpOutputProperty(type = "string"))))
            .jsonObject

    fun schemaFor(name: String): JsonObject =
        when {
            hasMcpReadPresentation(name) -> readSchema
            name == "health_check" || name == "validate_workspace" -> investigationSchema
            name == "refresh_workspace" -> refreshSchema
            name == "change" -> changeSchema
            else -> genericSchema
        }

    fun validates(name: String, content: JsonObject): Boolean =
        registry.getSchema(schemaFor(name).toString()).validate(content.toString(), InputFormat.JSON).isEmpty()

    /** The host document is already schema-admitted by ExistingIdeDocuments. Keep its typed cause intact. */
    fun hostedRejection(content: JsonObject): McpRejected? {
        if (content["type"] != JsonPrimitive("HOST_REJECTED")) return null
        val admitted =
            runCatching { wire.decodeFromJsonElement<McpHostedRejectionDocument>(content) }.getOrNull() ?: return null
        if (admitted.failure.isBlank()) return null
        return McpRejected(
            error =
                McpCallError(
                    McpCallFailure.HOSTED_OPERATION_REJECTED,
                    McpCallFailure.HOSTED_OPERATION_REJECTED.nextAction,
                    content,
                )
        )
    }

    fun failure(name: String, content: JsonObject?): McpResultSchemaEvidence? {
        if (content == null)
            return McpResultSchemaEvidence(McpResultSchemaFailure.UNPARSEABLE_DOCUMENT, McpResultSchemaField.UNKNOWN)
        val status =
            content["status"]
                ?: return McpResultSchemaEvidence(McpResultSchemaFailure.MISSING_STATUS, McpResultSchemaField.STATUS)
        if (status !is JsonPrimitive || !status.isString)
            return McpResultSchemaEvidence(McpResultSchemaFailure.INVALID_STATUS_TYPE, McpResultSchemaField.STATUS)
        val violation =
            registry.getSchema(schemaFor(name).toString()).validate(content.toString(), InputFormat.JSON).firstOrNull()
                ?: return null
        val field =
            when (violation.property ?: violation.instanceLocation.getName(-1)) {
                "status" -> McpResultSchemaField.STATUS
                "data" -> McpResultSchemaField.DATA
                "coverage" -> McpResultSchemaField.COVERAGE
                "basis" -> McpResultSchemaField.BASIS
                "stopReason" -> McpResultSchemaField.STOP_REASON
                "error" -> McpResultSchemaField.ERROR
                else -> McpResultSchemaField.UNKNOWN
            }
        return McpResultSchemaEvidence(McpResultSchemaFailure.SCHEMA_VIOLATION, field)
    }

    fun variant(content: JsonObject?): McpResultVariant =
        when ((content?.get("status") as? JsonPrimitive)?.content) {
            "complete" -> McpResultVariant.COMPLETE
            "partial" -> McpResultVariant.PARTIAL
            "qualified" -> McpResultVariant.QUALIFIED
            "rejected" -> McpResultVariant.REJECTED
            "unavailable" -> McpResultVariant.UNAVAILABLE
            else ->
                if (content?.get("type") == JsonPrimitive("HOST_REJECTED")) McpResultVariant.HOST_REJECTED
                else McpResultVariant.UNKNOWN
        }

    fun healthSummary(content: JsonObject?): String {
        val data = content?.get("data") as? JsonObject
        if (data == null) return "Workspace readiness rejected: ${errorCode(content)}"
        val root = data["workspaceBinding"]?.jsonPrimitive?.content ?: "unknown workspace"
        val readiness = data["readiness"]?.jsonPrimitive?.content ?: "unavailable"
        return "$root: $readiness"
    }

    fun validationSummary(content: JsonObject?): String {
        val data = content?.get("data") as? JsonObject
        if (data == null) return "Workspace validation rejected: ${errorCode(content)}"
        return listOf("discovery", "exactInspection", "sourceRead", "relation", "diagnostics").joinToString("; ") { name
            ->
            val probe = data[name] as? JsonObject
            "$name ${probe?.get("status")?.jsonPrimitive?.content ?: "unverified"}"
        }
    }

    private fun errorCode(content: JsonObject?): String =
        ((content?.get("error") as? JsonObject)?.get("code"))?.jsonPrimitive?.content ?: "unknown"

    private fun variant(status: String, vararg required: String): McpOutputVariant =
        McpOutputVariant(
            properties =
                mapOf("status" to McpOutputProperty(type = "string", const = status)) +
                    required.associateWith {
                        McpOutputProperty(
                            type =
                                if (it == "stopReason" || it == "issue" || it == "planIdentity") "string" else "object"
                        )
                    },
            required = listOf("status") + required,
        )
}

@Serializable
private data class McpHostedRejectionDocument(
    val type: String,
    val failure: String,
    val detail: kotlinx.serialization.json.JsonElement,
)

internal data class McpResultSchemaEvidence(
    val failure: McpResultSchemaFailure,
    val field: McpResultSchemaField,
)

@Serializable private data class McpOutputSchema(val type: String = "object", val oneOf: List<McpOutputVariant>)

@Serializable
private data class McpStatusSchema(
    val type: String = "object",
    val properties: Map<String, McpOutputProperty>,
    val required: List<String> = listOf("status"),
)

@Serializable
private data class McpOutputVariant(
    val type: String = "object",
    val properties: Map<String, McpOutputProperty>,
    val required: List<String>,
)

@Serializable private data class McpOutputProperty(val type: String, val const: String? = null)
