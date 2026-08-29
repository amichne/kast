package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledServerProjectionTest {
    @Test
    fun `installed schema owns broker tool shapes and exact cli bindings`() {
        val schema = installedSchema(
            operationRegistry = "{}",
            wireSchema = "{}",
            commandSurface = commandGraphFactory().surface,
        ).constructedDocument()
        val projection = Json.parseToJsonElement(schema.value)
            .jsonObject
            .getValue("serverProjection")
            .jsonObject
        val tools = projection.getValue("tools").jsonArray.map { it.jsonObject }
        val expectedPublicOperations = HostedOperationProjection.publicDefinitions
            .map { it.operation.id.value }
        val internalOperations = HostedOperationProjection.internalDefinitions
            .map { it.operation.id.value }

        assertEquals(1, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("kast", projection.getValue("namespace").jsonPrimitive.content)
        assertEquals(
            expectedPublicOperations,
            tools.map { it.getValue("operationId").jsonPrimitive.content },
        )
        assertEquals(
            expectedPublicOperations.map { it.replace('.', '_') },
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertTrue(tools.all { it.getValue("deferLoading").jsonPrimitive.content.toBoolean() })
        assertFalse(
            tools.any { it.getValue("operationId").jsonPrimitive.content in internalOperations },
        )

        val discover = tools.tool("symbol.discover")
        val variants = discover.getValue("inputSchema")
            .jsonObject
            .getValue("anyOf")
            .jsonArray
            .map { it.jsonObject }
        assertEquals(5, variants.size)
        assertEquals(
            listOf("name", "location", "structure", "text", "text"),
            variants.map { variant ->
                variant.getValue("properties")
                    .jsonObject
                    .getValue("mode")
                    .jsonObject
                    .getValue("const")
                    .jsonPrimitive
                    .content
            },
        )
        assertEquals(
            listOf("mode", "query", "kind", "match", "file", "offset", "scope", "limit"),
            discover.cliOptionFields(),
        )
        assertEquals(
            linkedMapOf(
                "workspace.inspect" to listOf("workspace", "inspect"),
                "topology.build" to listOf("topology", "build"),
                "symbol.discover" to listOf("symbol", "discover"),
                "symbol.resolve" to listOf("symbol", "resolve"),
                "symbol.describe" to listOf("symbol", "describe"),
                "traversal.run" to listOf("traversal", "run"),
                "change.plan" to listOf("change", "plan"),
                "change.apply" to listOf("change", "apply"),
                "change.verify" to listOf("change", "verify"),
                "change.recover" to listOf("change", "recover"),
            ),
            tools.associate { tool ->
                tool.getValue("operationId").jsonPrimitive.content to tool.cliCommand()
            },
        )
        assertEquals(
            linkedMapOf(
                "workspace.inspect" to emptyList(),
                "topology.build" to emptyList(),
                "symbol.discover" to
                    listOf("mode", "query", "kind", "match", "file", "offset", "scope", "limit"),
                "symbol.resolve" to listOf("candidate"),
                "symbol.describe" to listOf("selector"),
                "traversal.run" to
                    listOf("selector", "relation", "maximumDepth", "maximumResults"),
                "change.plan" to listOf("intent", "target", "declaration"),
                "change.apply" to listOf("plan"),
                "change.verify" to listOf("application"),
                "change.recover" to listOf("plan"),
            ),
            tools.associate { tool ->
                tool.getValue("operationId").jsonPrimitive.content to tool.cliOptionFields()
            },
        )
        assertEquals(1, tools.map { it.getValue("outputSchema") }.distinct().size)

        val changePlanProperties = tools.tool("change.plan")
            .getValue("inputSchema")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(
            "add-declaration",
            changePlanProperties.getValue("intent")
                .jsonObject
                .getValue("const")
                .jsonPrimitive
                .content,
        )
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(construction.failures)
    }

    private fun InstalledSchemaConstruction.constructedDocument(): CliJsonDocument = when (this) {
        is InstalledSchemaConstruction.Constructed -> document
        is InstalledSchemaConstruction.Rejected -> error(failure)
    }

    private fun JsonObject.cliCommand(): List<String> =
        getValue("invocation")
            .jsonObject
            .getValue("command")
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun JsonObject.cliOptionFields(): List<String> =
        getValue("invocation")
            .jsonObject
            .getValue("bindings")
            .jsonArray
            .map { binding ->
                binding.jsonObject.getValue("inputField").jsonPrimitive.content
            }

    private fun List<JsonObject>.tool(
        operationId: String,
    ): JsonObject = single {
        it.getValue("operationId").jsonPrimitive.content == operationId
    }
}
