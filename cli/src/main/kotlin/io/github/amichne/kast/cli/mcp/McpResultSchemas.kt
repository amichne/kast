package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.cli.installedPublicToolSemanticResultSchema
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Build-time preparation uses the canonical owners; runtime never regenerates a missing packaged contract. */
internal object McpResultSchemas {
    private val names = PublicToolIdentity.entries.map { it.toolName }.toSet() + "health_check"

    private val packaged = Resources { path ->
        checkNotNull(McpResultSchemas::class.java.getResourceAsStream(path)) {
                "Missing packaged MCP result schema: $path"
            }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    fun schemaFor(name: String): JsonObject = packaged.schemaFor(name)

    internal class Resources(private val read: (String) -> String) {
        private val schemas: Map<String, Lazy<JsonObject>> by lazy {
            val index = Json.decodeFromString(McpResultSchemaIndex.serializer(), read("/mcp-result-schemas.json"))
            check(index.contractVersion == PUBLIC_TOOL_CONTRACT_VERSION) { "Incompatible packaged MCP result schemas" }
            check(index.names == names) { "Incomplete packaged MCP result schemas" }
            index.names.associateWith { name ->
                lazy {
                    val document =
                        Json.decodeFromString(
                            McpResultSchemaDocument.serializer(),
                            read("/mcp-result-schemas/$name.json"),
                        )
                    check(document.contractVersion == index.contractVersion) {
                        "Incompatible packaged MCP result schema: $name"
                    }
                    check(document.name == name) { "Mismatched packaged MCP result schema: $name" }
                    document.schema
                }
            }
        }

        fun schemaFor(name: String): JsonObject =
            if (name in names) schemas.getValue(name).value else error("Unbound MCP tool result contract: $name")
    }

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
        ZipOutputStream(System.out).use { output ->
            for (name in names) {
                output.resource(
                    "mcp-result-schemas/$name.json",
                    McpResultSchemaDocument.serializer(),
                    McpResultSchemaDocument(PUBLIC_TOOL_CONTRACT_VERSION, name, generate(name)),
                )
            }
            output.resource(
                "mcp-result-schemas.json",
                McpResultSchemaIndex.serializer(),
                McpResultSchemaIndex(PUBLIC_TOOL_CONTRACT_VERSION, names),
            )
        }
    }

    private fun <T> ZipOutputStream.resource(name: String, serializer: KSerializer<T>, value: T) {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        write(Json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

/** Schema bodies are dynamic JSON Schema; the resource envelope is a closed versioned contract. */
@Serializable
private data class McpResultSchemaDocument(val contractVersion: Int, val name: String, val schema: JsonObject)

@Serializable private data class McpResultSchemaIndex(val contractVersion: Int, val names: Set<String>)
