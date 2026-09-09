package io.github.amichne.kast.appserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CodexHostIntegrationManifestMainTest {
    @Test
    fun `manifest preserves validated desktop startup without claiming UI qualification`(@TempDir temporary: Path) {
        val output = generate(temporary, installedReceipt())

        val manifest = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertEquals("VALIDATED", manifest.getValue("desktopStartupArguments").jsonPrimitive.content)
        assertEquals("UNQUALIFIED", manifest.getValue("desktopCompatibility").jsonPrimitive.content)
        assertEquals("NOT_REQUIRED", manifest.getValue("desktopDiscovery").jsonPrimitive.content)
        assertEquals("4", manifest.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(installedReceipt().getValue("privateService"), manifest.getValue("privateService"))
        assertFalse("standardAppServer" in manifest)
        assertEquals(setOf("CLI_REMOTE_CLIENT", "APP_SERVER_STDIO"), manifest.getValue("hostModes").jsonArray.map { it.jsonObject.getValue("mode").jsonPrimitive.content }.toSet())
    }

    @Test
    fun `missing desktop startup evidence cannot produce a manifest`(@TempDir temporary: Path) {
        assertRejected(temporary, JsonObject(installedReceipt() - "desktopStartupArguments"))
    }

    @Test
    fun `unvalidated desktop startup evidence cannot produce a manifest`(@TempDir temporary: Path) {
        assertRejected(temporary, JsonObject(installedReceipt() + ("desktopStartupArguments" to JsonPrimitive("UNVALIDATED"))))
    }

    @Test
    fun `unknown receipt fields remain rejected`(@TempDir temporary: Path) {
        assertRejected(temporary, JsonObject(installedReceipt() + ("unexpectedEvidence" to JsonPrimitive("VALIDATED"))))
    }

    @Test
    fun `legacy daemon evidence cannot substitute for private service receipt`(@TempDir temporary: Path) {
        val legacy = JsonObject(installedReceipt() - "privateService" + ("standardDaemon" to buildJsonObject {
            put("socketPath", "/fixture/.codex/app-server-control/app-server-control.sock")
            put("cliVersion", "0.153.4"); put("appServerVersion", "0.153.4")
        }))
        assertRejected(temporary, legacy)
    }

    @Test
    fun `unattached or unowned service evidence remains rejected`(@TempDir temporary: Path) {
        for ((path, value) in listOf(
            listOf("privateService", "ordinaryDaemonSocket") to "PRESENT",
            listOf("privateService", "phase") to "COORDINATOR_ONLY",
            listOf("privateService", "qualification", "service", "ownership") to "unobserved",
            listOf("privateService", "qualification", "coordinator", "observation", "hostAttachment") to "PENDING",
            listOf("privateService", "socketPath") to "/fixture/.codex/app-server-control/app-server-control.sock",
        )) assertRejected(temporary, installedReceipt().updated(path, JsonPrimitive(value)))
    }

    @Test
    fun `unknown nested private observation fields remain rejected`(@TempDir temporary: Path) {
        assertRejected(temporary, installedReceipt().updated(listOf("privateService", "qualification", "coordinator", "observation", "unexpected"), JsonPrimitive("VALIDATED")))
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

    private fun installedReceipt(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/codex-host/installed-private-service-receipt.json")).readText(),
    ).jsonObject

    private fun JsonObject.updated(path: List<String>, value: JsonElement): JsonObject = JsonObject(
        this + (path.first() to if (path.size == 1) value else getValue(path.first()).jsonObject.updated(path.drop(1), value)),
    )
}
