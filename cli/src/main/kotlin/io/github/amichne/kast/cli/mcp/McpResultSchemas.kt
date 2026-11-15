package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.cli.installedPublicToolSemanticResultSchema
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Build-time preparation uses the canonical owners; runtime never regenerates a missing packaged contract. */
internal object McpResultSchemas {
    private val names = PublicToolIdentity.entries.map { it.toolName }.toSet() + "health_check"

    private val packaged: Map<String, JsonObject> by lazy {
        val document =
            checkNotNull(McpResultSchemas::class.java.getResourceAsStream("/mcp-result-schemas.json")) {
                    "Missing packaged MCP result schemas"
                }
                .bufferedReader(Charsets.UTF_8)
                .use { Json.decodeFromString(McpResultSchemaDocument.serializer(), it.readText()) }
        check(document.contractVersion == PUBLIC_TOOL_CONTRACT_VERSION) { "Incompatible packaged MCP result schemas" }
        check(document.schemas.keys == names) { "Incomplete packaged MCP result schemas" }
        document.schemas
    }

    fun schemaFor(name: String): JsonObject =
        if (name in names) packaged.getValue(name) else error("Unbound MCP tool result contract: $name")

    internal fun generate(name: String): JsonObject =
        rootedResultSchema(
            when {
                PublicToolIdentity.entries.any {
                    it.toolName == name &&
                        it !in setOf(PublicToolIdentity.ADD_DECLARATION, PublicToolIdentity.REPLACE_BODY)
                } -> installedPublicToolSemanticResultSchema(PublicToolIdentity.entries.single { it.toolName == name })
                name == "health_check" -> healthResultSchema()
                name == "add_declaration" || name == "replace_body" ->
                    generatedRequestSchema(ChangeRunDocument.serializer())
                else -> error("Unbound MCP tool result contract: $name")
            }
        )

    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
        print(
            Json.encodeToString(
                McpResultSchemaDocument.serializer(),
                McpResultSchemaDocument(PUBLIC_TOOL_CONTRACT_VERSION, names.associateWith(::generate)),
            )
        )
    }
}

/** Schema bodies are dynamic JSON Schema; the resource envelope is a closed versioned contract. */
@Serializable private data class McpResultSchemaDocument(val contractVersion: Int, val schemas: Map<String, JsonObject>)
