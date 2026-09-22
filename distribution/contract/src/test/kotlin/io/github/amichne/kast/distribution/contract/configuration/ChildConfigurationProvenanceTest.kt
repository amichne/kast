package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChildConfigurationProvenanceTest {
    @Test
    fun `broker reloads saved assignments without upgrading their provenance`() {
        val configuration =
            (ResolvedKastConfiguration.resolve(
                    ConfigurationSources(
                        environment =
                            mapOf("KAST_CONFIGURATION_FILE" to "/tmp/settings.json", "SELECTED_CREDENTIAL" to "secret"),
                        savedInstallation =
                            listOf(
                                "KAST_READ_HOST_REFERENCE_ENTRIES" to "2048",
                                "KAST_GRADLE_IMPORT_VARIABLES" to "SELECTED_CREDENTIAL",
                            ),
                    )
                ) as Refinement.Refined)
                .value
        val broker = configuration.childEnvironment(ConfigurationChild.BROKER)
        assertEquals("/tmp/settings.json", broker["KAST_CONFIGURATION_FILE"])
        assertFalse(broker.containsKey("KAST_READ_HOST_REFERENCE_ENTRIES"))
        assertFalse(broker.containsKey("KAST_GRADLE_IMPORT_VARIABLES"))
        assertEquals("secret", broker["SELECTED_CREDENTIAL"])
        assertEquals(
            "SELECTED_CREDENTIAL",
            configuration.childIdentityInputs(ConfigurationChild.BROKER)["KAST_GRADLE_IMPORT_VARIABLES"],
        )
        assertEquals("<present>", configuration.childIdentityInputs(ConfigurationChild.BROKER)["SELECTED_CREDENTIAL"])
        assertEquals(
            ConfigurationSource.SAVED_INSTALLATION,
            configuration.inspection().single { it.key == "KAST_READ_HOST_REFERENCE_ENTRIES" }.source,
        )
    }
}
