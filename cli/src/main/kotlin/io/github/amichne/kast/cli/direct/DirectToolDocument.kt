package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.cli.InstalledHostedToolDocument
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.mcp.healthInputSchema
import io.github.amichne.kast.cli.mcp.validationInputSchema
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.SupportToolHost
import io.github.amichne.kast.protocol.registry.SupportToolIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One direct registration projected by both RPC and MCP. */
internal data class DirectToolDocument(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val readOnly: Boolean,
)

internal fun InstalledHostedToolDocument.directToolDocument(): DirectToolDocument {
    val effect = OperationEffect.entries.single { it.name.lowercase() == this.effect }
    val input = inputSchema as? JsonObject ?: error("Direct tool input schema must be an object")
    require(input["type"] == JsonPrimitive("object"))
    return DirectToolDocument(
        name,
        description,
        input,
        McpStructuredResults.schemaFor(name),
        effect == OperationEffect.NONE || effect == OperationEffect.INTELLIJ_READ,
    )
}

internal fun directSupportTools(): List<DirectToolDocument> =
    SupportToolIdentity.entries
        .filter { SupportToolHost.MCP in it.hosts }
        .map { identity ->
            val input =
                when (identity) {
                    SupportToolIdentity.HEALTH_CHECK -> healthInputSchema()
                    SupportToolIdentity.VALIDATE_WORKSPACE -> validationInputSchema()
                    SupportToolIdentity.WORKSPACE_LIFECYCLE -> error("Workspace lifecycle has no direct binding")
                }
            val objectInput = input as? JsonObject ?: error("Support tool input schema must be an object")
            require(objectInput["type"] == JsonPrimitive("object"))
            DirectToolDocument(
                identity.toolName,
                identity.description,
                objectInput,
                McpStructuredResults.schemaFor(identity.toolName),
                readOnly = true,
            )
        }
