package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
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

        assertEquals(1, projection.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("kast", projection.getValue("namespace").jsonPrimitive.content)
        assertEquals(
            listOf("symbol_discover", "symbol_resolve", "traversal_run"),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertEquals(
            listOf("symbol.discover", "symbol.resolve", "traversal.run"),
            tools.map { it.getValue("operationId").jsonPrimitive.content },
        )

        val discover = tools.first()
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
        assertEquals(listOf("symbol", "discover"), discover.cliCommand())
        assertEquals(listOf("symbol", "resolve"), tools[1].cliCommand())
        assertEquals(listOf("traversal", "run"), tools[2].cliCommand())
        assertEquals(
            listOf("selector", "relation", "maximumDepth", "maximumResults"),
            tools[2].cliOptionFields(),
        )
        assertEquals(1, tools.map { it.getValue("outputSchema") }.distinct().size)
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

    private fun kotlinx.serialization.json.JsonObject.cliCommand(): List<String> =
        getValue("invocation")
            .jsonObject
            .getValue("command")
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun kotlinx.serialization.json.JsonObject.cliOptionFields(): List<String> =
        getValue("invocation")
            .jsonObject
            .getValue("bindings")
            .jsonArray
            .map { binding ->
                binding.jsonObject.getValue("inputField").jsonPrimitive.content
            }
}
