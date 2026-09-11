package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationMutability
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationScope
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrokerOperationalCatalogueTest {
    @Test
    fun `inspection exposes broker admission queues persistence and qualification bounds`() {
        val expected =
            mapOf(
                "broker.calls.per_connection" to 8L,
                "broker.calls.per_provider" to 4L,
                "broker.catalog.maximum_descriptors" to 64L,
                "broker.catalog.maximum_bytes" to 1_048_576L,
                "broker.tool.argument.maximum_bytes" to 65_536L,
                "broker.tool.result.maximum_bytes" to 1_048_576L,
                "broker.workspace.queue.default" to 32L,
                "broker.workspace.queue.maximum" to 4_096L,
                "broker.workspace.queue.wait" to 10_000L,
                "broker.workspace.interaction" to 1_260_000L,
                "broker.workspace.events.maximum" to 128L,
                "broker.thread_catalog.maximum_bytes" to 4_194_304L,
                "broker.tasks.maximum" to 4_096L,
                "broker.runtime.connections.maximum" to 32L,
                "broker.codex.schema.default_bytes" to 16_777_216L,
                "broker.codex.schema.installed_bytes" to 33_554_432L,
                "broker.codex.schema.maximum_files" to 2_048L,
                "broker.codex.command.maximum_output_bytes" to 1_048_576L,
                "broker.process.maximum_input_bytes" to 4_194_304L,
                "broker.process.maximum_output_bytes" to 67_108_864L,
                "broker.gradle.maximum_output_bytes" to 524_288L,
                "broker.kast.schema.maximum_bytes" to 524_288L,
                "broker.readiness.exchange" to 3_000L,
                "broker.observer.diff.maximum_bytes" to 524_288L,
                "broker.observer.change.maximum_files" to 64L,
                "broker.host.argument.maximum_count" to 256L,
                "broker.host.argument.maximum_bytes" to 65_536L,
                "broker.registry.maximum_workspaces" to 256L,
                "broker.registry.maximum_bytes" to 1_048_576L,
                "broker.invocation_journal.maximum_bytes" to 2_097_152L,
                "broker.invocations.maximum" to 4_096L,
            )
        val limits = InstalledConfigurationSchema.document.operationalLimits
        assertEquals(limits.size, limits.map { it.key }.distinct().size)
        val catalogue = limits.associateBy { it.key }
        for ((key, value) in expected) {
            val declaration = catalogue[key]
            assertNotNull(declaration, "Missing operational limit: $key")
            declaration!!
            assertEquals(value, declaration.value, key)
            assertEquals(":app-server", declaration.owner, key)
            assertEquals(ConfigurationMutability.FIXED, declaration.mutability, key)
            assertTrue(declaration.sources.isEmpty(), key)
        }
        assertEquals(ConfigurationUnit.BYTES, catalogue.getValue("broker.catalog.maximum_bytes").unit)
        assertEquals(ConfigurationUnit.MILLISECONDS, catalogue.getValue("broker.workspace.queue.wait").unit)
        assertEquals(ConfigurationUnit.COUNT, catalogue.getValue("broker.workspace.queue.maximum").unit)
        assertEquals(ConfigurationScope.WORKSPACE, catalogue.getValue("broker.workspace.queue.default").scope)
        assertEquals(ConfigurationScope.INSTALLATION, catalogue.getValue("broker.registry.maximum_workspaces").scope)
    }
}
