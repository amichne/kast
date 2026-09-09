package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationMutability
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliOperationalCatalogueTest {
    @Test
    fun `inspection exposes fixed CLI input launch retirement and progress bounds`() {
        val expected = mapOf(
            "cli.request.maximum_bytes" to (4_194_304L to ConfigurationUnit.BYTES),
            "cli.launch.wrapper_timeout" to (5_000L to ConfigurationUnit.MILLISECONDS),
            "cli.launchctl.timeout" to (5_000L to ConfigurationUnit.MILLISECONDS),
            "cli.process_stop.wait_timeout" to (10_000L to ConfigurationUnit.MILLISECONDS),
            "cli.seed.project_state.maximum_bytes" to (4_194_304L to ConfigurationUnit.BYTES),
            "cli.bootstrap.state.maximum_bytes" to (16_384L to ConfigurationUnit.BYTES),
            "cli.startup.progress.maximum_lines" to (256L to ConfigurationUnit.COUNT),
            "cli.startup.progress.heartbeat" to (5_000L to ConfigurationUnit.MILLISECONDS),
            "cli.endpoint.maximum_path_bytes" to (103L to ConfigurationUnit.BYTES),
        )
        val catalogue = InstalledConfigurationSchema.document.operationalLimits.associateBy { it.key }
        for ((key, value) in expected) {
            val declaration = catalogue[key]
            assertNotNull(declaration, "Missing operational limit: $key")
            declaration!!
            assertEquals(value.first, declaration.value, key)
            assertEquals(value.second, declaration.unit, key)
            assertEquals(":cli", declaration.owner, key)
            assertEquals(ConfigurationMutability.FIXED, declaration.mutability, key)
            assertTrue(declaration.sources.isEmpty(), key)
        }
    }
}
