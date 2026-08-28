package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledSchemaSerializationTest {
    @Test
    fun `composition rejects a non-installed test layout as finite bootstrap data`() {
        val construction = InstalledKastCliComposition().create()

        assertTrue(
            construction is KastCliCompositionConstruction.Rejected,
            "expected non-installed test classes to be rejected, got $construction",
        )
    }

    @Test
    fun `generated installed schema embeds open resources inside a closed outer document`() {
        val operationRegistry = "{\"operation\":\"registry\"}"
        val wireSchema = "{\"wire\":true}"
        val surface = commandGraphFactory().surface
        val schema = installedSchema(operationRegistry, wireSchema, surface)
            .constructedDocument()

        val document = Json.decodeFromString(
            InstalledSchemaFixture.serializer(),
            schema.value,
        )

        assertEquals(1, document.schemaVersion)
        assertEquals(Json.parseToJsonElement(operationRegistry), document.operationRegistry)
        assertEquals(Json.parseToJsonElement(wireSchema), document.wireSchema)
        assertEquals(surface.localFlags, document.cliProjection.localFlags)
        assertEquals(
            surface.lifecycleCommands.map { it.command },
            document.cliProjection.lifecycleCommands,
        )
        assertEquals(
            surface.semanticCommands.map { it.usage },
            document.cliProjection.commands,
        )
    }

    @Test
    fun `installed schema retains the exact rejected open resource`() {
        val surface = commandGraphFactory().surface
        val valid = "{}"

        listOf(
            InstalledSchemaResource.OPERATION_REGISTRY to installedSchema(
                "[]",
                valid,
                surface,
            ),
            InstalledSchemaResource.WIRE_SCHEMA to installedSchema(
                valid,
                "[]",
                surface,
            ),
        ).forEach { (resource, construction) ->
            assertEquals(
                InstalledSchemaConstruction.Rejected(
                    InstalledSchemaFailure(resource, CliOpenJsonObjectFailure.NOT_AN_OBJECT),
                ),
                construction,
            )
        }
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
}

@Serializable
private data class InstalledSchemaFixture(
    val schemaVersion: Int,
    val operationRegistry: JsonElement,
    val wireSchema: JsonElement,
    val cliProjection: InstalledCliProjectionFixture,
)

@Serializable
private data class InstalledCliProjectionFixture(
    val localFlags: List<String>,
    val lifecycleCommands: List<String>,
    val commands: List<String>,
)
