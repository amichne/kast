package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

@Serializable private data class NativeTestCommand(val command: String)

@Serializable
private data class NativeTestInventory(
    val schema: String = "kast.cli.describe.v1",
    @SerialName("public_contract_version") val contract: Int = 18,
    val catalog: String = "COMPLETE",
    val section: String = "inventory",
    val content: List<NativeTestCommand> =
        listOf("query", "query.plan", "result.read", "result.resume", "source.read", "describe")
            .map(::NativeTestCommand),
)

class NativeGuidanceContractTest {
    @Test
    fun `internal inventory uses JSON when the user selects TOON`(@TempDir temporary: Path) {
        val (root, home) = integrationFixture(temporary, HarnessConnection.CODEX_MCP)
        val installation = selectedInstallation(root)
        val original = assertInstanceOf<BundledManifestRead.Read>(readBundledManifest(installation)).manifest
        val payloads =
            listOf("bin/kast-query", "bin/kast-query-complete").map { relative ->
                val path = Files.writeString(installation.resolve(relative), "unused fixture payload")
                path.toFile().setExecutable(true, false)
                BundledPayload(relative, "sha256:${sha256(path)}", 493)
            }
        Files.writeString(
            installation.resolve("installation.json"),
            managementJson.encodeToString(original.copy(payloadFiles = original.payloadFiles + payloads)),
        )
        val receipt = requireOwnedExecutable(root)
        val launcher = Path.of(receipt.executable)
        val command =
            listOf(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    NativeInventoryProbe::class.java.name,
                )
                .joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        Files.writeString(launcher, "#!/bin/sh\nexec $command \"${'$'}@\"\n")
        launcher.toFile().setExecutable(true, false)
        writeManagementReceipt(root, receipt.copy(executableSha256 = sha256(launcher)))
        assertEquals(
            PluginObservation.Observed(Unit),
            inspectGuidanceCommand(root, home, mapOf("KAST_OUTPUT_TOON" to "1")),
        )
    }

    @Test
    fun `native inventory admits supported contract and rejects missing commands or another schema`() {
        val accepted = NativeTestInventory()
        assertEquals(PluginObservation.Observed(Unit), admitNativeCliInventory(managementJson.encodeToString(accepted)))
        for (document in
            listOf(
                accepted.copy(schema = "different"),
                accepted.copy(contract = 17),
                accepted.copy(content = accepted.content.dropLast(1)),
                accepted.copy(section = "input"),
            )) assertEquals(
            PluginObservation.Rejected(PluginInstallFailure.PayloadRejected),
            admitNativeCliInventory(managementJson.encodeToString(document)),
        )
    }
}

internal object NativeInventoryProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.toList() == listOf("describe"))
        check(System.getenv("KAST_OUTPUT_TOON") == "0")
        println(managementJson.encodeToString(NativeTestInventory()))
    }
}
