package io.github.amichne.kast.cli.mcp

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.cli.installedPublicToolSemanticResultSchema
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** MCP advertises and validates the owning operation's semantic document. */
internal object McpStructuredResults {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

    val healthSchema: JsonObject = healthResultSchema()

    fun schemaFor(name: String): JsonObject =
        rootedResultSchema(
            when {
                PublicToolIdentity.entries.any {
                    it.toolName == name &&
                        it !in setOf(PublicToolIdentity.ADD_DECLARATION, PublicToolIdentity.REPLACE_BODY)
                } -> installedPublicToolSemanticResultSchema(PublicToolIdentity.entries.single { it.toolName == name })
                name == "health_check" -> healthSchema
                name == "add_declaration" || name == "replace_body" ->
                    generatedRequestSchema(ChangeRunDocument.serializer())
                else -> error("Unbound MCP tool result contract: $name")
            }
        )

    fun validates(name: String, content: JsonObject): Boolean =
        registry.getSchema(schemaFor(name).toString()).validate(content.toString(), InputFormat.JSON).isEmpty()

    fun failure(name: String, content: JsonObject?): McpResultSchemaEvidence? {
        if (content == null)
            return McpResultSchemaEvidence(McpResultSchemaFailure.UNPARSEABLE_DOCUMENT, McpResultSchemaField.UNKNOWN)
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
            "qualified" -> McpResultVariant.QUALIFIED
            "rejected" -> McpResultVariant.REJECTED
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

    private fun errorCode(content: JsonObject?): String =
        ((content?.get("error") as? JsonObject)?.get("code"))?.jsonPrimitive?.content ?: "unknown"
}

@Serializable
private data class McpObjectUnionSchema(
    val type: String,
    val anyOf: List<kotlinx.serialization.json.JsonElement>,
    val discriminator: McpSchemaDiscriminator? = null,
    @SerialName("\$defs") val definitions: JsonObject? = null,
)

@Serializable private data class McpSchemaDiscriminator(val propertyName: String)

private val resultSchemaJson = Json { explicitNulls = false }

/** Branch schemas and definitions remain dynamic JSON Schema; discriminator metadata has one closed typed shape. */
internal fun rootedResultSchema(schema: JsonObject): JsonObject {
    require(schema.keys.all { it == "anyOf" || it == "\$defs" || it == "type" || it == "discriminator" }) {
        "Unsupported semantic result schema root"
    }
    require(schema["type"] == null || schema["type"] == JsonPrimitive("object")) {
        "Semantic result schema root must be an object"
    }
    val variants = schema["anyOf"] as? JsonArray ?: error("Semantic result must be a closed union")
    return resultSchemaJson
        .encodeToJsonElement(
            McpObjectUnionSchema(
                type = "object",
                anyOf = variants,
                discriminator =
                    schema["discriminator"]?.let {
                        resultSchemaJson.decodeFromJsonElement(McpSchemaDiscriminator.serializer(), it)
                    },
                definitions = schema["\$defs"] as? JsonObject,
            )
        )
        .jsonObject
}

internal data class McpResultSchemaEvidence(
    val failure: McpResultSchemaFailure,
    val field: McpResultSchemaField,
)
