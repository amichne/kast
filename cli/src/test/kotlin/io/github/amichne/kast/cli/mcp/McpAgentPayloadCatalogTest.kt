package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.direct.directSupportTools
import io.github.amichne.kast.cli.direct.directToolDocument
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Records the real MCP serializer for conversion experiments, without a workspace or semantic effect. */
class McpAgentPayloadCatalogTest {
    @Test
    fun `each session exposes exactly one generated query projection`() {
        for (profile in PublicToolCatalog.entries) {
            val installed = installedHostedBootstrap().tools.associateBy { it.name }
            val catalog =
                PublicToolIdentity.entries.map { identity ->
                    val tool = installed.getValue(identity.toolName)
                    tool
                        .directToolDocument()
                        .copy(
                            inputSchema = PublicToolContract.parameters(identity, profile),
                            description = identity.descriptionFor(profile),
                        )
                } + directSupportTools()
            val request =
                McpRequest(
                    id = JsonPrimitive(1),
                    method = "tools/list",
                    params = mcpWire.encodeToJsonElement(CatalogMeta.serializer(), CatalogMeta()),
                )
            val input = mcpWire.encodeToString(McpRequest.serializer(), request) + "\n"
            val output = ByteArrayOutputStream()
            KastMcpServer(
                    catalog,
                    invoke = { _, _ -> error("Catalog capture must not invoke a tool") },
                    root = { CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) },
                    diagnostic = PrintStream(ByteArrayOutputStream()),
                )
                .run(BufferedInputStream(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))), PrintStream(output))
            val actual =
                Json.parseToJsonElement(output.toString(Charsets.UTF_8))
                    .jsonObject
                    .getValue("result")
                    .jsonObject
                    .getValue("tools")
                    .jsonArray
            val query = actual.single { it.jsonObject.getValue("name").jsonPrimitive.content == "query_symbols" }
            assertEquals(catalog.size, actual.size)
            assertEquals(
                PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS, profile),
                query.jsonObject["inputSchema"],
            )
            val directory = Path.of("build/reports/agent-payload")
            Files.createDirectories(directory)
            Files.write(directory.resolve(profile.name.lowercase() + "-tools-list.json"), output.toByteArray())
        }
    }
}

@Serializable
private data class CatalogMeta(
    @kotlinx.serialization.Required
    @kotlinx.serialization.SerialName("_meta")
    val meta: McpRequestMeta = McpRequestMeta(MODERN_PROTOCOL_VERSION, emptyMcpArguments)
)
