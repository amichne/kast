package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationConfigurationValidationTest {
    @Test
    fun `staged configuration is admitted without the retired CLI`(@TempDir root: Path) {
        val configuration = Files.writeString(root.toRealPath().resolve("environment"), "KAST_DEBUG=0\n")
        val observations = mutableListOf<InstallationConfigurationValidationObservation>()

        assertEquals(
            InstallationConfigurationValidationOutcome.ADMITTED,
            validateStagedConfiguration(configuration, observations::add),
        )
        val document = Json.parseToJsonElement(observations.single().toJson()).jsonObject
        assertEquals(setOf("event", "outcome"), document.keys)
        assertEquals("kast_installation_configuration", document.getValue("event").jsonPrimitive.content)
        assertEquals("ADMITTED", document.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `invalid staged configuration rejects with bounded stage evidence`(@TempDir root: Path) {
        val configuration = Files.writeString(root.toRealPath().resolve("environment"), "KAST_UNKNOWN_SETTING=1\n")
        val observations = mutableListOf<InstallationConfigurationValidationObservation>()

        assertEquals(
            InstallationConfigurationValidationOutcome.RESOLUTION_REJECTED,
            validateStagedConfiguration(configuration, observations::add),
        )
        val document = Json.parseToJsonElement(observations.single().toJson()).jsonObject
        assertEquals(setOf("event", "outcome"), document.keys)
        assertEquals("kast_installation_configuration", document.getValue("event").jsonPrimitive.content)
        assertEquals("RESOLUTION_REJECTED", document.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun `missing staged file rejects at source admission`(@TempDir root: Path) {
        val observations = mutableListOf<InstallationConfigurationValidationObservation>()
        assertEquals(
            InstallationConfigurationValidationOutcome.SOURCE_REJECTED,
            validateStagedConfiguration(root.toRealPath().resolve("missing"), observations::add),
        )
        assertEquals(InstallationConfigurationValidationOutcome.SOURCE_REJECTED, observations.single().outcome)
    }

    @Test
    fun `invalid broker endpoint rejects at owner admission`(@TempDir root: Path) {
        val configuration =
            Files.writeString(
                root.toRealPath().resolve("environment"),
                "KAST_APP_SERVER_PUBLIC_ENDPOINT=unsupported\n",
            )
        val observations = mutableListOf<InstallationConfigurationValidationObservation>()
        assertEquals(
            InstallationConfigurationValidationOutcome.OWNER_REJECTED,
            validateStagedConfiguration(configuration, observations::add),
        )
        assertEquals(InstallationConfigurationValidationOutcome.OWNER_REJECTED, observations.single().outcome)
    }
}
