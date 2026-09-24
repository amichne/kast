package io.github.amichne.kast.cli.mcp

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    val genericSchema: JsonObject =
        wire
            .encodeToJsonElement(McpStatusSchema(properties = mapOf("status" to McpOutputProperty(type = "string"))))
            .jsonObject

    fun schemaFor(name: String): JsonObject =
        when {
            hasMcpReadPresentation(name) -> readSchema
            name == "health_check" || name == "validate_workspace" -> investigationSchema
            else -> genericSchema
        }

    fun validates(name: String, content: JsonObject): Boolean =
        registry.getSchema(schemaFor(name).toString()).validate(content.toString(), InputFormat.JSON).isEmpty()

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
                    required.associateWith { McpOutputProperty(type = if (it == "stopReason") "string" else "object") },
            required = listOf("status") + required,
        )
}

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
