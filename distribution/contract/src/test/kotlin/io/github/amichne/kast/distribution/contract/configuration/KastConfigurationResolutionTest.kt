package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastConfigurationResolutionTest {
    @Test
    fun `Java home keeps canonical authority and explicit source precedence`() {
        val resolved =
            admitted(
                ConfigurationSources(
                    environment = mapOf("KAST_GRADLE_JAVA_HOME" to "/java0"),
                    savedInstallation = listOf("KAST_GRADLE_JAVA_HOME" to "/java1"),
                    savedWorkspace = listOf("KAST_GRADLE_JAVA_HOME" to "/java0"),
                    commandLine = listOf("KAST_GRADLE_JAVA_HOME" to "/java1"),
                )
            )
        assertEquals("/java1", resolved.inspection().single { it.key == "KAST_GRADLE_JAVA_HOME" }.value)
        val entry = resolved.inspection().single { it.key == "KAST_GRADLE_JAVA_HOME" }
        assertEquals(ConfigurationSource.COMMAND_LINE, entry.source)
        assertEquals(
            listOf(
                ConfigurationSource.PROCESS_ENVIRONMENT,
                ConfigurationSource.SAVED_WORKSPACE,
                ConfigurationSource.SAVED_INSTALLATION,
            ),
            entry.overriddenSources,
        )
    }

    @Test
    fun `explicit malformed assignments never fall through to valid lower precedence`() {
        for (value in listOf("", "255m", "8G", "2147483648m", "1g\n")) {
            assertInstanceOf(
                Refinement.Rejected::class.java,
                ResolvedKastConfiguration.resolve(
                    ConfigurationSources(
                        environment = mapOf("KAST_READ_HOST_REFERENCE_ENTRIES" to value),
                        savedInstallation = listOf("KAST_READ_HOST_REFERENCE_ENTRIES" to "8"),
                    )
                ),
            )
        }
    }

    @Test
    fun `unknown duplicate unsupported and test-only assignments reject without values`() {
        val cases =
            listOf(
                ConfigurationSources(environment = mapOf("KAST_UNKNOWN" to "private-value")) to
                    ConfigurationFailure.UNKNOWN_KEY,
                ConfigurationSources(
                    savedInstallation =
                        listOf("KAST_READ_HOST_REFERENCE_ENTRIES" to "1", "KAST_READ_HOST_REFERENCE_ENTRIES" to "2")
                ) to ConfigurationFailure.DUPLICATE_ASSIGNMENT,
                ConfigurationSources(savedWorkspace = listOf("KAST_INSTALL_ROOT" to "/fixture")) to
                    ConfigurationFailure.UNSUPPORTED_SOURCE,
                ConfigurationSources(environment = mapOf("KAST_INSTALL_PROCESS_KILL_COMMAND" to "/fixture/kill")) to
                    ConfigurationFailure.TEST_ONLY_INPUT,
            )
        for ((sources, expected) in cases) {
            val result = rejected(sources)
            assertEquals(expected, result.reason)
            assertFalse(result.toString().contains("private-value"))
        }
    }

    @Test
    fun `child projection preserves read limit and explicitly delegated environment only`() {
        val resolved =
            admitted(
                ConfigurationSources(
                    environment =
                        mapOf(
                            "KAST_READ_HOST_REFERENCE_ENTRIES" to "8",
                            "KAST_GRADLE_IMPORT_VARIABLES" to "REPOSITORY_TOKEN",
                            "REPOSITORY_TOKEN" to "private-value",
                            "UNSELECTED_TOKEN" to "unselected-value",
                            "GRADLE_USER_HOME" to "/fixture/gradle",
                        )
                )
            )
        val broker = resolved.childEnvironment(ConfigurationChild.BROKER)
        assertEquals("8", broker["KAST_READ_HOST_REFERENCE_ENTRIES"])
        assertEquals("/fixture/gradle", broker["GRADLE_USER_HOME"])
        assertEquals("private-value", broker["REPOSITORY_TOKEN"])
        assertFalse(broker.containsKey("UNSELECTED_TOKEN"))
        assertFalse(resolved.inspection().toString().contains("private-value"))
        assertFalse(resolved.toString().contains("private-value"))
        assertEquals("8", resolved.childEnvironment(ConfigurationChild.SIDECAR)["KAST_READ_HOST_REFERENCE_ENTRIES"])
    }

    @Test
    fun `missing or reserved delegated variable fails before child launch`() {
        for (names in listOf("MISSING_TOKEN", "JAVA_TOOL_OPTIONS", "KAST_READ_HOST_REFERENCE_ENTRIES", "A,A")) {
            assertEquals(
                ConfigurationFailure.DELEGATED_ENVIRONMENT_REJECTED,
                rejected(ConfigurationSources(environment = mapOf("KAST_GRADLE_IMPORT_VARIABLES" to names))).reason,
            )
        }
    }

    @Test
    fun `catalogue is deterministic unique and projects read limit default from its owner`() {
        val catalogue = KastConfigurationCatalogue.declarations
        assertEquals(catalogue.map { it.key }.sorted(), catalogue.map { it.key })
        assertEquals(catalogue.size, catalogue.map { it.key }.toSet().size)
        val limit = catalogue.single { it.key == "KAST_READ_HOST_REFERENCE_ENTRIES" }
        assertEquals("16384", limit.defaultValue)
        assertTrue(ConfigurationChild.BROKER in limit.children)
    }

    @Test
    fun `resolved snapshot cannot be changed by editing caller environment`() {
        val inputs = mutableMapOf("KAST_READ_HOST_REFERENCE_ENTRIES" to "8", "KAST_OPTS" to "-Dsecret=private-value")
        val resolved = admitted(ConfigurationSources(environment = inputs))
        inputs["KAST_READ_HOST_REFERENCE_ENTRIES"] = "1"
        assertEquals("8", resolved.inspection().single { it.key == "KAST_READ_HOST_REFERENCE_ENTRIES" }.value)
        assertEquals("8", resolved.childEnvironment(ConfigurationChild.BROKER)["KAST_READ_HOST_REFERENCE_ENTRIES"])
        assertFalse(resolved.inspection().toString().contains("private-value"))
        assertFalse(resolved.childEnvironment(ConfigurationChild.BROKER).containsKey("KAST_OPTS"))
    }

    @Test
    fun `version owned runtime directories reject workspace overrides at source admission`() {
        for (key in listOf("KAST_RUNTIME_DIRECTORY")) {
            val resolution =
                ResolvedKastConfiguration.resolve(
                    ConfigurationSources(savedWorkspace = listOf(key to "/another/workspace-owned-directory"))
                )
            assertTrue(resolution is Refinement.Rejected, "$key incorrectly admits workspace ownership")
            assertEquals(ConfigurationFailure.UNSUPPORTED_SOURCE, (resolution as Refinement.Rejected).failure.reason)
            assertEquals(
                ConfigurationScope.INSTALLATION,
                KastConfigurationCatalogue.declarations.single { it.key == key }.scope,
            )
        }
    }

    private fun admitted(sources: ConfigurationSources): ResolvedKastConfiguration =
        when (val result = ResolvedKastConfiguration.resolve(sources)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Unexpected ${result.failure}")
        }

    private fun rejected(sources: ConfigurationSources): ConfigurationRejection =
        when (val result = ResolvedKastConfiguration.resolve(sources)) {
            is Refinement.Rejected -> result.failure
            is Refinement.Refined -> error("Unexpected admitted configuration")
        }
}
