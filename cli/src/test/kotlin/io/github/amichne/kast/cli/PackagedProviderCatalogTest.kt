package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PackagedProviderCatalogTest {
    @Test
    fun `packaged contract preserves canonical hosted schemas without executable metadata`() {
        val graph = CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created
        val legacy = installedServerProjection(graph.factory.surface)
        val packaged = PackagedProviderCatalog.document()
        assertEquals(legacy.hostedBootstrap, packaged.serverProjection.hostedBootstrap)
        assertEquals(
            CanonicalAgentToolDefinitions.all.map { it.name.value },
            packaged.serverProjection.hostedBootstrap.tools.map { it.name },
        )
        val encoded = Json.encodeToJsonElement(packaged).jsonObject
        assertEquals(setOf("schemaVersion", "serverProjection"), encoded.keys)
        val projection = encoded.getValue("serverProjection").jsonObject
        assertEquals(setOf("schemaVersion", "namespace", "hostedBootstrap"), projection.keys)
        assertFalse("cliInvocations" in projection)
    }
}
