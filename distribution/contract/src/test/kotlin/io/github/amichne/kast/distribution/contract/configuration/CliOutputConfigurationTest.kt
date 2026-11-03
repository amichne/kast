package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CliOutputConfigurationTest {
    @Test
    fun `output selection remains process-local and cannot reach Host children`() {
        for ((raw, normalized) in listOf("" to "1", "0" to "0", "1" to "1")) {
            val resolved =
                when (
                    val result =
                        ResolvedKastConfiguration.resolve(
                            ConfigurationSources(environment = mapOf("KAST_OUTPUT_TOON" to raw))
                        )
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> error("Unexpected configuration rejection: ${result.failure}")
                }
            val setting = resolved.inspection().single { it.key == "KAST_OUTPUT_TOON" }
            assertEquals(normalized, setting.value)
            assertEquals(ConfigurationSource.PROCESS_ENVIRONMENT, setting.source)
            for (child in ConfigurationChild.entries) assertFalse(
                resolved.childEnvironment(child).containsKey("KAST_OUTPUT_TOON")
            )
            assertFalse(resolved.launchEnvironment().variables.containsKey("KAST_OUTPUT_TOON"))
        }
    }

    @Test
    fun `unconfigured output selects TOON without reaching Host children`() {
        val resolved =
            when (val result = ResolvedKastConfiguration.resolve(ConfigurationSources())) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> error("Unexpected configuration rejection: ${result.failure}")
            }
        val setting = resolved.inspection().single { it.key == "KAST_OUTPUT_TOON" }
        assertEquals("1", setting.value)
        assertEquals(ConfigurationSource.DEFAULT, setting.source)
        for (child in ConfigurationChild.entries) assertFalse(
            resolved.childEnvironment(child).containsKey("KAST_OUTPUT_TOON")
        )
        assertFalse(resolved.launchEnvironment().variables.containsKey("KAST_OUTPUT_TOON"))
    }

    @Test
    fun `saved and command-line output settings reject instead of changing process presentation`() {
        for (sources in
            listOf(
                ConfigurationSources(savedInstallation = listOf("KAST_OUTPUT_TOON" to "1")),
                ConfigurationSources(savedWorkspace = listOf("KAST_OUTPUT_TOON" to "1")),
                ConfigurationSources(commandLine = listOf("KAST_OUTPUT_TOON" to "1")),
            )) {
            when (val result = ResolvedKastConfiguration.resolve(sources)) {
                is Refinement.Refined -> error("Unexpected saved output setting")
                is Refinement.Rejected -> assertEquals(ConfigurationFailure.UNSUPPORTED_SOURCE, result.failure.reason)
            }
        }
    }
}
