package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkerCapacityConfigurationTest {
    @Test fun `declared worker policy defaults to one resident and accepts explicit concurrency`() {
        val defaults = (ResolvedKastConfiguration.resolve(ConfigurationSources()) as Refinement.Refined).value.inspection().associateBy { it.key }
        assertEquals("1", defaults["KAST_WORKER_RESIDENT_LIMIT"]?.value)
        val configured = ResolvedKastConfiguration.resolve(ConfigurationSources(environment = mapOf(
            "KAST_WORKER_RESIDENT_LIMIT" to "2",
            "KAST_WORKER_STARTUP_LIMIT" to "1",
            "KAST_WORKER_AGGREGATE_MIB" to "32768",
            "KAST_WORKER_NATIVE_MIB" to "1024",
            "KAST_WORKER_GRADLE_MIB" to "2048",
        )))
        assertInstanceOf(Refinement.Refined::class.java, configured)
    }

    @Test fun `broker reloads saved assignments without upgrading their provenance`() {
        val configuration = (ResolvedKastConfiguration.resolve(ConfigurationSources(
            environment = mapOf("KAST_CONFIGURATION_FILE" to "/tmp/settings.json", "SELECTED_CREDENTIAL" to "secret"),
            savedInstallation = listOf("KAST_INDEXER_MAX_HEAP" to "2g", "KAST_GRADLE_IMPORT_VARIABLES" to "SELECTED_CREDENTIAL"),
        )) as Refinement.Refined).value
        val broker = configuration.childEnvironment(ConfigurationChild.BROKER)
        assertEquals("/tmp/settings.json", broker["KAST_CONFIGURATION_FILE"])
        assertFalse(broker.containsKey("KAST_INDEXER_MAX_HEAP"))
        assertFalse(broker.containsKey("KAST_GRADLE_IMPORT_VARIABLES"))
        assertEquals("secret", broker["SELECTED_CREDENTIAL"])
        assertEquals("SELECTED_CREDENTIAL", configuration.childIdentityInputs(ConfigurationChild.BROKER)["KAST_GRADLE_IMPORT_VARIABLES"])
        assertEquals("<present>", configuration.childIdentityInputs(ConfigurationChild.BROKER)["SELECTED_CREDENTIAL"])
        assertEquals(ConfigurationSource.SAVED_INSTALLATION, configuration.inspection().single { it.key == "KAST_INDEXER_MAX_HEAP" }.source)
    }
}
