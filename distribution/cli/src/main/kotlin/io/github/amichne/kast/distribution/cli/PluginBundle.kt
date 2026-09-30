package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@JvmInline
internal value class PluginVersion(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAXIMUM_PLUGIN_VERSION_CHARACTERS)
    }
}

private const val MAXIMUM_PLUGIN_VERSION_CHARACTERS = 256

@Serializable private data class BundledPluginVersion(val name: String, val version: String)

private val pluginManifestJson = Json { ignoreUnknownKeys = true }
private val requiredPluginFiles =
    setOf(
        ".agents/plugins/marketplace.json",
        "plugins/kast/.codex-plugin/plugin.json",
        "plugins/kast/.mcp.json",
        "plugins/kast/skills/kast/SKILL.md",
    )

internal class AdmittedPluginBundle
private constructor(
    val marketplace: Path,
    val version: PluginVersion,
) {
    val plugin: Path = marketplace.resolve("plugins/kast")

    companion object {
        @Suppress("ReturnCount", "ComplexCondition", "CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
        fun admit(root: Path): PluginObservation<AdmittedPluginBundle> {
            val installation =
                try {
                    selectedInstallation(root)
                } catch (_: ManagementRejected) {
                    return PluginObservation.Rejected(PluginInstallFailure.InstallationUnavailable)
                }
            val manifest =
                when (val read = readBundledManifest(installation)) {
                    is BundledManifestRead.Read -> read.manifest
                    BundledManifestRead.Unavailable,
                    BundledManifestRead.Invalid ->
                        return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                }
            if (
                manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
                    manifest.installationRoot != installation.toString()
            )
                return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
            return try {
                val bundle = installation.resolve(AGENT_TOOLS_RELATIVE_PATH)
                if (bundle.toRealPath() != bundle)
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val paths =
                    Files.walk(bundle).use { stream ->
                        stream.limit(ControlDistributionLimits.maximumEntryCount.toLong() + 1).toList()
                    }
                if (
                    paths.size > ControlDistributionLimits.maximumEntryCount ||
                        paths.any {
                            Files.isSymbolicLink(it) ||
                                (!Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) &&
                                    !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS))
                        }
                )
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val files = paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                val relativeFiles = files.map { installation.relativize(it).toString().replace('\\', '/') }.toSet()
                val entries = manifest.payloadFiles.filter { it.path.startsWith("$AGENT_TOOLS_RELATIVE_PATH/") }
                if (
                    entries.map { it.path }.toSet() != relativeFiles ||
                        entries.size != relativeFiles.size ||
                        !requiredPluginFiles.all { "$AGENT_TOOLS_RELATIVE_PATH/$it" in relativeFiles } ||
                        files.any { Files.size(it) > ControlDistributionLimits.maximumPayloadBytes } ||
                        files.sumOf(Files::size) > ControlDistributionLimits.maximumPayloadBytes ||
                        entries.any { it.sha256 != "sha256:${sha256(installation.resolve(it.path))}" }
                )
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val launcher = verifiedBundledSource(installation, HarnessConnection.CODEX_MCP)
                if (!Files.isExecutable(launcher))
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val raw =
                    readBoundedFile(bundle.resolve("plugins/kast/.codex-plugin/plugin.json"), 65536)
                        ?: return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val version = pluginManifestJson.decodeFromString<BundledPluginVersion>(raw)
                if (
                    version.name != "kast" ||
                        version.version.isBlank() ||
                        version.version.length > MAXIMUM_PLUGIN_VERSION_CHARACTERS
                )
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                PluginObservation.Observed(AdmittedPluginBundle(bundle, PluginVersion(version.version)))
            } catch (_: IOException) {
                PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
            } catch (_: ManagementRejected) {
                PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
            } catch (_: SerializationException) {
                PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
            }
        }
    }
}
