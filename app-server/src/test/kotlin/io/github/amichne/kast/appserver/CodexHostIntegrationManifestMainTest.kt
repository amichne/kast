package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class CodexHostIntegrationManifestMainTest {
    @Test
    fun `manifest preserves validated desktop startup without claiming UI qualification`(@TempDir temporary: Path) {
        val output = generate(temporary, installedReceipt())

        val manifest = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertEquals("VALIDATED", manifest.getValue("desktopStartupArguments").jsonPrimitive.content)
        assertEquals("UNQUALIFIED", manifest.getValue("desktopCompatibility").jsonPrimitive.content)
        assertEquals("UNQUALIFIED", manifest.getValue("desktopDiscovery").jsonPrimitive.content)
        assertEquals("5", manifest.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(installedReceipt().getValue("canonicalService"), manifest.getValue("canonicalService"))
        assertFalse("standardAppServer" in manifest)
        assertEquals(
            setOf("CLI_REMOTE_CLIENT", "APP_SERVER_STDIO"),
            manifest
                .getValue("hostModes")
                .jsonArray
                .map { it.jsonObject.getValue("mode").jsonPrimitive.content }
                .toSet(),
        )
    }

    @Test
    fun `missing desktop startup evidence cannot produce a manifest`(@TempDir temporary: Path) {
        assertRejected(
            temporary,
            installedReceipt().updated(listOf("desktopStartupArguments"), kotlinx.serialization.json.JsonNull),
        )
    }

    @Test
    fun `unvalidated desktop startup evidence cannot produce a manifest`(@TempDir temporary: Path) {
        assertRejected(
            temporary,
            installedReceipt().updated(listOf("desktopStartupArguments"), JsonPrimitive("UNVALIDATED")),
        )
    }

    @Test
    fun `unknown receipt fields remain rejected`(@TempDir temporary: Path) {
        assertRejected(temporary, installedReceipt().updated(listOf("unexpectedEvidence"), JsonPrimitive("VALIDATED")))
    }

    @Test
    fun `legacy daemon evidence cannot substitute for private service receipt`(@TempDir temporary: Path) {
        val legacy = installedReceipt().updated(listOf("canonicalService"), kotlinx.serialization.json.JsonNull)
        assertRejected(temporary, legacy)
    }

    @Test
    fun `unattached or unowned service evidence remains rejected`(@TempDir temporary: Path) {
        for ((path, value) in
            listOf(
                listOf("canonicalService", "beforeAttachment", "phase") to "pending",
                listOf("canonicalService", "afterDetach", "publicSocketAndOwnership") to "UNQUALIFIED",
                listOf("canonicalService", "afterDetach", "publicEndpointKind") to "private",
                listOf("canonicalService", "ordinaryDaemonDiscovery") to "UNQUALIFIED",
            )) assertRejected(temporary, installedReceipt().updated(path, JsonPrimitive(value)))
    }

    @Test
    fun `unknown nested private observation fields remain rejected`(@TempDir temporary: Path) {
        assertRejected(
            temporary,
            installedReceipt()
                .updated(
                    listOf("canonicalService", "afterDetach", "unexpected"),
                    JsonPrimitive("VALIDATED"),
                ),
        )
    }

    private fun assertRejected(temporary: Path, receipt: JsonObject) {
        val failure = assertThrows<IllegalArgumentException> { generate(temporary, receipt) }
        assertEquals("Installed acceptance receipt is invalid", failure.message)
        assertFalse(Files.exists(temporary.resolve("manifest.json")))
    }

    private fun generate(temporary: Path, receipt: JsonObject): Path {
        val input = temporary.resolve("installed-receipt.json")
        val output = temporary.resolve("manifest.json")
        Files.writeString(input, receipt.toString())
        CodexHostIntegrationManifestMain.main(arrayOf(output.toString(), input.toString()))
        return output
    }

    private fun installedReceipt(): JsonObject =
        Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/codex-host/installed-canonical-service-receipt.json")).readText()
            )
            .jsonObject

    private fun JsonObject.updated(path: List<String>, value: JsonElement): JsonObject =
        JsonObject(
            this +
                (path.first() to
                    if (path.size == 1) value else getValue(path.first()).jsonObject.updated(path.drop(1), value))
        )
}
