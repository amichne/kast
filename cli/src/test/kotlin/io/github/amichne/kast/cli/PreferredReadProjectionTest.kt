package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class PreferredReadProjectionTest {
    @Test
    fun `installed projection advances incompatible catalog version`() {
        assertEquals("13", projection().getValue("schemaVersion").jsonPrimitive.content)
    }

    @Test
    fun `installed catalog and cli bindings advertise preferred names only`() {
        val projection = projection()
        val tools = projection.getValue("hostedBootstrap").jsonObject.getValue("tools").jsonArray
        val invocations = projection.getValue("cliInvocations").jsonObject.getValue("operations").jsonArray
        for ((operation, preferred) in
            listOf("relation.read" to "read_relations", "traversal.run" to "traverse_relations")) {
            val tool =
                tools.single { it.jsonObject.getValue("operationId").jsonPrimitive.content == operation }.jsonObject
            val invocation =
                invocations
                    .single { it.jsonObject.getValue("operationId").jsonPrimitive.content == operation }
                    .jsonObject
            assertEquals(preferred, tool.getValue("name").jsonPrimitive.content)
            assertEquals(preferred, invocation.getValue("toolName").jsonPrimitive.content)
            assertEquals(
                operation.split('.'),
                invocation.getValue("invocation").jsonObject.getValue("command").jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
        }
        assertFalse(
            tools.any {
                it.jsonObject.getValue("name").jsonPrimitive.content in setOf("semantic_query", "impact_analyze")
            }
        )
    }

    private fun projection() =
        Json.parseToJsonElement(
                installedSchema(
                        operationRegistry = Json.encodeToString(EmptyMetadata),
                        wireSchema = Json.encodeToString(EmptyMetadata),
                        commandSurface =
                            (CliCommandGraphFactory.create(canonicalCliRequestPreparers())
                                    as CliCommandGraphConstruction.Created)
                                .factory
                                .surface,
                    )
                    .constructedDocument()
                    .value
            )
            .jsonObject
            .getValue("serverProjection")
            .jsonObject

    private fun InstalledSchemaConstruction.constructedDocument(): CanonicalJsonDocument =
        when (this) {
            is InstalledSchemaConstruction.Constructed -> document
            is InstalledSchemaConstruction.Rejected -> fail("Expected installed schema: $this")
        }

    @Serializable private data object EmptyMetadata
}
