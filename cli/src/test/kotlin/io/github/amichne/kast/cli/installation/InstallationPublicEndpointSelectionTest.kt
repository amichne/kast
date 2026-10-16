package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class InstallationPublicEndpointSelectionTest {
    @Test
    fun `every endpoint selection observation encodes the independent fixed shape and required defaults`() {
        val cases = observations()
        val resource = checkNotNull(javaClass.getResource("/installation/public-endpoint-observations.expected.json"))
        val expected = Json.decodeFromString<List<ExpectedObservation>>(resource.readText())
        assertEquals(expected.size, cases.size)
        assertEquals(InstallationPublicEndpointSelectionReason.entries.toSet(), cases.map { it.reason }.toSet())
        for ((observation, shape) in cases.zip(expected)) {
            val actual = Json.parseToJsonElement(observation.toJson()).jsonObject
            assertEquals(setOf("event", "endpoint", "reason"), actual.keys)
            assertEquals(shape.event, actual.getValue("event").jsonPrimitive.content)
            assertEquals(shape.endpoint, actual.getValue("endpoint").jsonPrimitive.content)
            assertEquals(shape.reason, actual.getValue("reason").jsonPrimitive.content)
        }
    }

    private fun observations(): List<InstallationPublicEndpointObservation> =
        listOf(
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.FRESH_DEFAULT,
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.SAVED_CONFIGURATION,
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.EXPLICIT,
                BrokerPublicEndpointMode.PRIVATE to
                    InstallationPublicEndpointSelectionReason.LEGACY_SHARED_ENDPOINT_MIGRATED,
            )
            .map { (endpoint, reason) -> InstallationPublicEndpointObservation(endpoint = endpoint, reason = reason) }

    @Test
    fun `fresh unspecified endpoint selects private without external effects`() {
        assertEquals(
            Refinement.Refined(InstallationConfigurationSelection.FreshDefault),
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, null),
        )
    }

    @Test
    fun `explicit fresh endpoint preserves the selected mode`() {
        val selected =
            selectInstallationConfiguration(InstallationPublicEndpointSelection.ExplicitPrivate, null)
                as Refinement.Refined
        assertEquals(BrokerPublicEndpointMode.PRIVATE, selected.value.endpoint)
        assertEquals(InstallationPublicEndpointSelectionReason.EXPLICIT, selected.value.reason)
    }

    @Test
    fun `saved endpoint admission retains its mode and reason`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("prior"))
        Files.createDirectory(root.resolve("config"))
        Files.writeString(root.resolve("config/environment"), "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n")
        val selected =
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, root) as Refinement.Refined
        assertEquals(BrokerPublicEndpointMode.PRIVATE, selected.value.endpoint)
        assertEquals(InstallationPublicEndpointSelectionReason.SAVED_CONFIGURATION, selected.value.reason)
    }

    @Test
    fun `invalid prior settings retain each finite admission stage`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("prior"))
        Files.createDirectory(root.resolve("config"))
        val configuration = root.resolve("config/environment")
        val failures =
            listOf(
                "invalid record" to InstallationConfigurationFailure.SOURCE_REJECTED,
                "KAST_UNKNOWN_SETTING=1\n" to InstallationConfigurationFailure.RESOLUTION_REJECTED,
                "KAST_DEBUG=0\nKAST_DEBUG=1\n" to InstallationConfigurationFailure.RESOLUTION_REJECTED,
                "KAST_APP_SERVER_PUBLIC_ENDPOINT=unsupported\n" to InstallationConfigurationFailure.OWNER_REJECTED,
            )
        for ((content, failure) in failures) {
            Files.writeString(configuration, content)
            assertEquals(
                Refinement.Rejected(failure),
                selectInstallationConfiguration(
                    InstallationPublicEndpointSelection.ExplicitPrivate,
                    root,
                ),
            )
            assertEquals(content, Files.readString(configuration))
        }
    }

    @Test
    fun `missing and linked prior settings reject at physical source admission`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("prior"))
        Files.createDirectory(root.resolve("config"))
        assertEquals(
            Refinement.Rejected(InstallationConfigurationFailure.SOURCE_REJECTED),
            selectInstallationConfiguration(
                InstallationPublicEndpointSelection.Unspecified,
                root,
            ),
        )
        val target = Files.writeString(root.resolve("outside"), "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n")
        Files.createSymbolicLink(root.resolve("config/environment"), target)
        assertEquals(
            Refinement.Rejected(InstallationConfigurationFailure.SOURCE_REJECTED),
            selectInstallationConfiguration(
                InstallationPublicEndpointSelection.Unspecified,
                root,
            ),
        )
        assertEquals("KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n", Files.readString(target))
    }

    @Serializable private data class ExpectedObservation(val event: String, val endpoint: String, val reason: String)
}
