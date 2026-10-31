package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val nativeGuidanceJson = Json { ignoreUnknownKeys = true }

@Serializable private data class NativeCliCommandDescriptor(val command: String)

@Serializable
private data class NativeCliInventory(
    val schema: String,
    @SerialName("public_contract_version") val contract: Int,
    val catalog: String,
    val section: String,
    val content: List<NativeCliCommandDescriptor>,
)

internal fun admitNativeCliInventory(raw: String): PluginObservation<Unit> =
    try {
        val inventory = nativeGuidanceJson.decodeFromString<NativeCliInventory>(raw)
        val commands = inventory.content.map { it.command }
        if (validNativeHeader(inventory) && validNativeCommands(inventory, commands)) PluginObservation.Observed(Unit)
        else PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
    } catch (_: SerializationException) {
        PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
    }

internal fun inspectGuidanceCommand(root: Path, home: Path, environment: Map<String, String>): PluginObservation<Unit> =
    try {
        val receipt = requireOwnedExecutable(root)
        val installation = selectedInstallation(root)
        val manifest = readBundledManifest(installation)
        if (
            manifest !is BundledManifestRead.Read ||
                PublicQueryLauncher.Admitted.fromInstallation(installation, manifest.manifest) !is
                    PublicQueryLauncher.Admitted
        )
            PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
        else
            when (
                val result =
                    pluginProcessResult(
                        runGuidanceProvider(
                            listOf(receipt.executable, "describe"),
                            environment + mapOf("HOME" to home.toString(), "KAST_INSTALL_ROOT" to root.toString()),
                        )
                    )
            ) {
                is PluginObservation.Rejected -> result
                is PluginObservation.Observed -> admitNativeCliInventory(result.value)
            }
    } catch (_: ManagementRejected) {
        PluginObservation.Rejected(PluginInstallFailure.InstallationUnavailable)
    }

private fun validNativeHeader(inventory: NativeCliInventory): Boolean =
    inventory.schema == "kast.cli.describe.v1" &&
        inventory.contract == NATIVE_PUBLIC_CONTRACT &&
        inventory.catalog == "COMPLETE"

private fun validNativeCommands(inventory: NativeCliInventory, commands: List<String>): Boolean =
    inventory.section == "inventory" &&
        commands.distinct() == commands &&
        commands
            .toSet()
            .containsAll(setOf("query", "query.plan", "result.read", "result.resume", "source.read", "describe"))

private const val NATIVE_PUBLIC_CONTRACT = 18
