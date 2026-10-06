package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.appserver.BrokerPublicEndpointMode
import io.github.amichne.kast.appserver.CodexControlSocketAvailability
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
                BrokerPublicEndpointMode.CODEX_CONTROL to InstallationPublicEndpointSelectionReason.FRESH_DEFAULT,
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.OCCUPIED_CODEX_CONTROL,
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.UNAVAILABLE_CODEX_CONTROL,
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.SAVED_CONFIGURATION,
                BrokerPublicEndpointMode.CODEX_CONTROL to InstallationPublicEndpointSelectionReason.SAVED_CONFIGURATION,
                BrokerPublicEndpointMode.PRIVATE to InstallationPublicEndpointSelectionReason.EXPLICIT,
                BrokerPublicEndpointMode.CODEX_CONTROL to InstallationPublicEndpointSelectionReason.EXPLICIT,
            )
            .map { (endpoint, reason) -> InstallationPublicEndpointObservation(endpoint = endpoint, reason = reason) }

    @Test
    fun `occupied fresh endpoint records the finite fallback reason and exact chosen mode`() {
        val boundary = ScriptedAvailability(listOf(CodexControlSocketAvailability.OCCUPIED))
        val selected =
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, null, boundary::observe)
                as Refinement.Refined
        boundary.assertConsumed()
        assertEquals(InstallationConfigurationSelection.FreshOccupiedCodexControl, selected.value)
        val document =
            Json.parseToJsonElement(
                    InstallationPublicEndpointObservation(
                            endpoint = selected.value.endpoint,
                            reason = selected.value.reason,
                        )
                        .toJson()
                )
                .jsonObject
        assertEquals(setOf("event", "endpoint", "reason"), document.keys)
        assertEquals("kast_installation_public_endpoint", document.getValue("event").jsonPrimitive.content)
        assertEquals("PRIVATE", document.getValue("endpoint").jsonPrimitive.content)
        assertEquals("OCCUPIED_CODEX_CONTROL", document.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `unknown fresh endpoint availability preserves uncertainty and selects private`() {
        val boundary = ScriptedAvailability(listOf(CodexControlSocketAvailability.UNAVAILABLE))
        assertEquals(
            Refinement.Refined(InstallationConfigurationSelection.FreshUnavailableCodexControl),
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, null, boundary::observe),
        )
        boundary.assertConsumed()
    }

    @Test
    fun `explicit fresh endpoint never inspects occupancy`() {
        for (endpoint in BrokerPublicEndpointMode.entries) {
            val boundary = ScriptedAvailability(emptyList())
            val selected =
                selectInstallationConfiguration(
                    InstallationPublicEndpointSelection.Explicit(endpoint),
                    null,
                    boundary::observe,
                )
                    as Refinement.Refined
            boundary.assertConsumed()
            assertEquals(endpoint, selected.value.endpoint)
            assertEquals(InstallationPublicEndpointSelectionReason.EXPLICIT, selected.value.reason)
        }
    }

    @Test
    fun `saved endpoint admission retains its reason and never inspects occupancy`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("prior"))
        Files.createDirectory(root.resolve("config"))
        Files.writeString(root.resolve("config/environment"), "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n")
        val boundary = ScriptedAvailability(emptyList())
        val selected =
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, root, boundary::observe)
                as Refinement.Refined
        boundary.assertConsumed()
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
            val boundary = ScriptedAvailability(emptyList())
            assertEquals(
                Refinement.Rejected(failure),
                selectInstallationConfiguration(
                    InstallationPublicEndpointSelection.Explicit(BrokerPublicEndpointMode.PRIVATE),
                    root,
                    boundary::observe,
                ),
            )
            boundary.assertConsumed()
            assertEquals(content, Files.readString(configuration))
        }
    }

    @Test
    fun `missing and linked prior settings reject at physical source admission`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("prior"))
        Files.createDirectory(root.resolve("config"))
        val boundary = ScriptedAvailability(emptyList())
        assertEquals(
            Refinement.Rejected(InstallationConfigurationFailure.SOURCE_REJECTED),
            selectInstallationConfiguration(
                InstallationPublicEndpointSelection.Unspecified,
                root,
                boundary::observe,
            ),
        )
        val target = Files.writeString(root.resolve("outside"), "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n")
        Files.createSymbolicLink(root.resolve("config/environment"), target)
        assertEquals(
            Refinement.Rejected(InstallationConfigurationFailure.SOURCE_REJECTED),
            selectInstallationConfiguration(
                InstallationPublicEndpointSelection.Unspecified,
                root,
                boundary::observe,
            ),
        )
        boundary.assertConsumed()
        assertEquals("KAST_APP_SERVER_PUBLIC_ENDPOINT=private\n", Files.readString(target))
    }

    private class ScriptedAvailability(private val observations: List<CodexControlSocketAvailability>) {
        private var calls = 0

        fun observe(): CodexControlSocketAvailability {
            check(calls < observations.size) { "Unexpected endpoint availability observation" }
            return observations[calls++]
        }

        fun assertConsumed() = assertEquals(observations.size, calls, "Unconsumed endpoint availability observation")
    }

    @Serializable private data class ExpectedObservation(val event: String, val endpoint: String, val reason: String)
}
