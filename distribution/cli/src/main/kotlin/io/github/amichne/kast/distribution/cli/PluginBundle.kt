package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@JvmInline
internal value class PluginVersion(val value: String) {
    init {
        require(validPluginVersion(value))
    }
}

private const val MAXIMUM_PLUGIN_VERSION_CHARACTERS = 256

private fun validPluginVersion(raw: String): Boolean =
    raw.length <= MAXIMUM_PLUGIN_VERSION_CHARACTERS &&
        Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(raw)

@Serializable
private data class BundledPluginVersion(
    val name: String,
    val version: String,
    val mcpServers: String? = null,
    val hooks: String? = null,
    val skills: String = "./skills/",
)

@Serializable private data class BundledPluginMcp(val mcpServers: BundledPluginServers)

@Serializable private data class BundledPluginServers(val kast: BundledPluginTransport)

@Serializable
private data class BundledPluginTransport(
    val command: String,
    val args: List<String>,
    // The manifest owns these dynamic environment variable names and values.
    val env: Map<String, String>? = null,
    @SerialName("env_vars") val envVars: List<String> = emptyList(),
    val cwd: String? = null,
)

private val pluginManifestJson = Json { ignoreUnknownKeys = true }
private val requiredPluginFiles =
    setOf(
        ".agents/plugins/marketplace.json",
        ".github/plugin/marketplace.json",
        "plugins/kast/.codex-plugin/plugin.json",
        "plugins/kast/.github/plugin/plugin.json",
        "plugins/kast/skills/kast/SKILL.md",
        "plugins/kast/skills/kast/public-cli.md",
    )

internal class AdmittedPluginBundle
private constructor(
    val marketplace: Path,
    val version: PluginVersion,
    private val transport: CodexTransport,
    val composition: HarnessComposition,
    val base: Path,
    private val resources: List<BundledPayload>,
) {
    val plugin: Path = marketplace.resolve("plugins/kast")

    fun ownsTransport(server: CodexServer): Boolean =
        server.name == "kast" &&
            server.transport.copy(env = server.transport.env.orEmpty()) in
                listOf(transport, transport.copy(args = transport.args.map { it.replace("/compositions/mcp/", "/") }))

    fun ownsMarketplace(raw: String): Boolean =
        try {
            val path = Path.of(raw)
            path == base || path == base.resolve("compositions/mcp")
        } catch (_: IllegalArgumentException) {
            false
        }

    fun resourcesMatch(directory: Path): Boolean =
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || directory.toRealPath() != directory) false
            else {
                val paths =
                    Files.walk(directory).use {
                        it.limit(ControlDistributionLimits.maximumEntryCount.toLong() + 1).toList()
                    }
                val files = paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                paths.size <= ControlDistributionLimits.maximumEntryCount &&
                    paths.all {
                        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) ||
                            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
                    } &&
                    files.map { directory.relativize(it).toString() }.toSet() == resources.map { it.path }.toSet() &&
                    resources.all { it.sha256 == "sha256:${sha256(directory.resolve(it.path))}" }
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    companion object {
        @Suppress("ReturnCount", "ComplexCondition", "CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
        fun admit(
            root: Path,
            composition: HarnessComposition = HarnessComposition.CliOnly,
        ): PluginObservation<AdmittedPluginBundle> {
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
                val base = installation.resolve(AGENT_TOOLS_RELATIVE_PATH)
                val bundle = if (composition == HarnessComposition.CliOnly) base else base.resolve("compositions/mcp")
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
                val prefix = installation.relativize(bundle).toString()
                val entries = manifest.payloadFiles.filter { it.path.startsWith("$prefix/") }
                if (
                    entries.map { it.path }.toSet() != relativeFiles ||
                        entries.size != relativeFiles.size ||
                        !requiredPluginFiles.all { "$prefix/$it" in relativeFiles } ||
                        files.any { Files.size(it) > ControlDistributionLimits.maximumPayloadBytes } ||
                        files.sumOf(Files::size) > ControlDistributionLimits.maximumPayloadBytes ||
                        entries.any { it.sha256 != "sha256:${sha256(installation.resolve(it.path))}" }
                )
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                if (
                    composition == HarnessComposition.CliWithMcp &&
                        !Files.isExecutable(verifiedBundledSource(installation, HarnessConnection.CODEX_MCP))
                )
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val raw =
                    readBoundedFile(bundle.resolve("plugins/kast/.codex-plugin/plugin.json"), 65536)
                        ?: return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val version = pluginManifestJson.decodeFromString<BundledPluginVersion>(raw)
                if (
                    version.name != "kast" ||
                        !validPluginVersion(version.version) ||
                        version.hooks != null ||
                        version.skills != "./skills/" ||
                        version.mcpServers != if (composition == HarnessComposition.CliWithMcp) "./.mcp.json" else null
                )
                    return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val mcp =
                    readBoundedFile(base.resolve("compositions/mcp/plugins/kast/.mcp.json"), 65536)
                        ?: readBoundedFile(base.resolve("plugins/kast/.mcp.json"), 65536)
                        ?: return PluginObservation.Rejected(PluginInstallFailure.PayloadRejected)
                val server = pluginManifestJson.decodeFromString<BundledPluginMcp>(mcp).mcpServers.kast
                PluginObservation.Observed(
                    AdmittedPluginBundle(
                        bundle,
                        PluginVersion(version.version),
                        CodexTransport(
                            "stdio",
                            server.command,
                            server.args,
                            server.env.orEmpty(),
                            server.envVars,
                            server.cwd,
                        ),
                        composition,
                        base,
                        entries
                            .filter { it.path.startsWith("$prefix/plugins/kast/") }
                            .map {
                                it.copy(path = it.path.removePrefix("$prefix/plugins/kast/"))
                            },
                    )
                )
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
