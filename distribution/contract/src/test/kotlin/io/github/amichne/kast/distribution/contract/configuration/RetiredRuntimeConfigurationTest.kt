package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetiredRuntimeConfigurationTest {
    @Test
    fun `removed runtime setup inputs fail closed at every configuration source`() {
        for (key in retiredKeys) {
            for (sources in
                listOf(
                    ConfigurationSources(environment = mapOf(key to "/private/retired-runtime")),
                    ConfigurationSources(commandLine = listOf(key to "/private/retired-runtime")),
                    ConfigurationSources(savedInstallation = listOf(key to "/private/retired-runtime")),
                    ConfigurationSources(savedWorkspace = listOf(key to "/private/retired-runtime")),
                )) {
                val result = ResolvedKastConfiguration.resolve(sources)
                assertTrue(result is Refinement.Rejected, "$key must not configure a retired runtime")
                val failure = (result as Refinement.Rejected).failure
                assertEquals(ConfigurationFailure.UNKNOWN_KEY, failure.reason, key)
                assertEquals(key, failure.key)
                assertFalse(failure.toString().contains("/private/"))
            }
        }
    }

    @Test
    fun `catalogue contains no removed setup keys or retired module owners`() {
        val declarations = KastConfigurationCatalogue.declarations
        assertTrue(declarations.none { it.key in retiredKeys })
        assertTrue(declarations.none { it.owner in setOf(":indexer", ":workspace:intellij") })
    }

    private val retiredKeys =
        setOf(
            "KAST_NETWORK_CONFIG",
            "KAST_TRUST_DONOR_JAVA_HOME",
            "KAST_IDE_CONFIG_HOME",
            "KAST_INSTALL_RUNTIME_ARCHIVE",
            "KAST_INSTALL_RUNTIME_SHA256",
            "KAST_RUNTIME_BASE_URL",
            "KAST_LOCAL_RUNTIME_ARCHIVE",
            "KAST_SEMANTIC_RUNTIME_ARCHIVE",
        )
}
