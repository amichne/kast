package io.github.amichne.kast.appserver

import kotlinx.serialization.json.Json
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

    private fun installedReceipt(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("taskId", "HOST-08")
        put("outcome", "COMPLETE")
        put("parentClosure", "CLEAN")
        put("stdoutProtocol", "JSONL_ONLY")
        put("initialize", "VALIDATED")
        put("threadStart", "VALIDATED")
        put("facadeRole", "app-server-stdio")
        put("codexVersion", "codex-cli 0.153.4")
        for (field in listOf(
            "catalogProjectionSha256", "codexProtocolSha256", "kastContractSha256",
            "kastExecutableSha256", "kastFacadeSha256", "codexExecutableSha256",
        )) {
            put(field, "sha256:" + "1".repeat(64))
        }
        put("catalogToolNames", buildJsonArray { add(JsonPrimitive("query")) })
        put("standardDaemon", buildJsonObject {
            put("socketPath", "/fixture/.codex/app-server-control/app-server-control.sock")
            put("cliVersion", "0.153.4")
            put("appServerVersion", "0.153.4")
        })
        put("persistentServiceAfterDetach", "VALIDATED")
        put("desktopCompatibility", "UNQUALIFIED")
        put("desktopDiscovery", "SYNTHETIC_METADATA")
        put("desktopStartupArguments", "VALIDATED")
    }
}
