package io.github.amichne.kast.idea

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileKind
import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
import io.github.amichne.kast.api.contract.compatibility.ReleaseRevision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

object KastProjectOpenProfileAutoInit {
    fun execute(
        workspaceRoot: Path,
        config: KastConfig,
    ): ProjectOpenProfileAutoInitResult = if (isMacosHost()) {
        executeWithDependencies(workspaceRoot, config)
    } else {
        executeWithConfiguredBinary(workspaceRoot, config)
    }

    internal fun executeWithConfiguredBinary(
        workspaceRoot: Path,
        config: KastConfig,
        loadCliIdentity: (Path) -> CliBuildIdentity? = ::loadConfiguredCliIdentity,
        prepareWorkspace: (PluginWorkspaceBootstrapRequest) -> PluginWorkspaceBootstrapResult =
            PluginWorkspaceBootstrap::prepare,
    ): ProjectOpenProfileAutoInitResult =
        executeWithCliAuthorityResolver(
            workspaceRoot = workspaceRoot,
            config = config,
            resolveCliAuthority = {
                val binary = Path.of(config.cli.binaryPath.value).toAbsolutePath().normalize()
                if (!Files.isRegularFile(binary)) {
                    CliAuthorityLoadResult.Rejected("Kast CLI binary is missing at $binary")
                } else {
                    val identity = loadCliIdentity(binary)
                    if (identity == null) {
                        CliAuthorityLoadResult.Rejected(
                            "Configured Kast CLI at $binary did not report a valid build identity; update the CLI and reopen this exact project.",
                        )
                    } else {
                        CliAuthorityLoadResult.Loaded(
                            binary = binary,
                            version = identity.version,
                            revision = identity.revision,
                        )
                    }
                }
            },
            prepareWorkspace = prepareWorkspace,
        )

    internal fun executeWithDependencies(
        workspaceRoot: Path,
        config: KastConfig,
        loadHomebrewReceipt: () -> MacosHomebrewReceiptLoadResult = MacosHomebrewReceiptLoader::load,
        prepareWorkspace: (PluginWorkspaceBootstrapRequest) -> PluginWorkspaceBootstrapResult =
            PluginWorkspaceBootstrap::prepare,
    ): ProjectOpenProfileAutoInitResult =
        executeWithCliAuthorityResolver(
            workspaceRoot = workspaceRoot,
            config = config,
            resolveCliAuthority = {
                when (val result = loadHomebrewReceipt()) {
                    is MacosHomebrewReceiptLoadResult.Loaded ->
                        CliAuthorityLoadResult.Loaded(
                            binary = result.receipt.cliBinary,
                            version = result.receipt.cliVersion,
                            revision = result.receipt.cliRevision,
                        )
                    is MacosHomebrewReceiptLoadResult.Rejected ->
                        CliAuthorityLoadResult.Rejected(result.message)
                }
            },
            prepareWorkspace = prepareWorkspace,
        )

    private fun executeWithCliAuthorityResolver(
        workspaceRoot: Path,
        config: KastConfig,
        resolveCliAuthority: (PluginVersion) -> CliAuthorityLoadResult,
        prepareWorkspace: (PluginWorkspaceBootstrapRequest) -> PluginWorkspaceBootstrapResult,
    ): ProjectOpenProfileAutoInitResult {
        if (!config.projectOpen.profileAutoInit.value) {
            return ProjectOpenProfileAutoInitResult.Skipped("disabled")
        }
        if (config.projectOpen.profile.kind != ProjectOpenProfileKind.JETBRAINS_PLUGIN) {
            return ProjectOpenProfileAutoInitResult.Skipped("unsupported profile")
        }
        if (!workspaceRoot.hasGradleMarker()) {
            return ProjectOpenProfileAutoInitResult.Skipped("not a Gradle project")
        }

        val pluginVersion = kastPluginVersion()
            ?: return ProjectOpenProfileAutoInitResult.Failed(
                "Kast plugin version resource is missing or invalid; refusing workspace setup.",
            )
        val pluginRevision = kastPluginRevision()
            ?: return ProjectOpenProfileAutoInitResult.Failed(
                "Kast plugin revision resource is missing or invalid; refusing workspace setup.",
            )
        val cliAuthority = when (val result = resolveCliAuthority(pluginVersion)) {
            is CliAuthorityLoadResult.Loaded -> result
            is CliAuthorityLoadResult.Rejected ->
                return ProjectOpenProfileAutoInitResult.Failed(result.message)
        }
        if (
            pluginVersion.value == cliAuthority.version.value &&
            pluginRevision != cliAuthority.revision
        ) {
            return ProjectOpenProfileAutoInitResult.Failed(
                "Kast plugin revision ${pluginRevision.value} does not match CLI revision ${cliAuthority.revision.value}; update both before workspace setup.",
            )
        }
        val request = PluginWorkspaceBootstrapRequest(
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
            cliBinary = cliAuthority.binary,
            cliVersion = cliAuthority.version,
            cliRevision = cliAuthority.revision,
            pluginVersion = pluginVersion,
            pluginRevision = pluginRevision,
        )
        return when (val result = prepareWorkspace(request)) {
            is PluginWorkspaceBootstrapResult.Prepared ->
                ProjectOpenProfileAutoInitResult.Installed(
                    metadataPath = result.metadataPath,
                    backups = result.backups,
                )
            is PluginWorkspaceBootstrapResult.Rejected ->
                ProjectOpenProfileAutoInitResult.Failed(result.message)
        }
    }

    private fun isMacosHost(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private sealed interface CliAuthorityLoadResult {
        data class Loaded(
            val binary: Path,
            val version: CliImplementationVersion,
            val revision: ReleaseRevision,
        ) : CliAuthorityLoadResult

        data class Rejected(val message: String) : CliAuthorityLoadResult
    }

    private fun Path.hasGradleMarker(): Boolean =
        listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .any { marker -> Files.isRegularFile(resolve(marker)) }

    private val LOG = Logger.getInstance(KastProjectOpenProfileAutoInit::class.java)

    fun log(result: ProjectOpenProfileAutoInitResult) {
        when (result) {
            is ProjectOpenProfileAutoInitResult.Installed ->
                LOG.info(
                    "Kast project-open workspace setup prepared ${result.metadataPath}" +
                        if (result.backups.isEmpty()) "" else " with ${result.backups.size} backup(s)",
                )
            is ProjectOpenProfileAutoInitResult.Skipped ->
                LOG.info("Kast project-open workspace setup skipped: ${result.reason}")
            is ProjectOpenProfileAutoInitResult.Failed ->
                LOG.warn("Kast project-open workspace setup failed: ${result.message}")
        }
    }
}

private fun loadConfiguredCliIdentity(binary: Path): CliBuildIdentity? {
    val process = runCatching {
        ProcessBuilder(binary.toString(), "--output", "json", "version")
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return null
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    return parseCliBuildIdentityDocument(output)
}

internal fun parseCliBuildIdentityDocument(output: String): CliBuildIdentity? {
    val document = runCatching { Json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return null
    if (document.keys != setOf("type", "version", "releaseRevision", "schemaVersion")) return null
    val type = runCatching { document["type"]?.jsonPrimitive }.getOrNull() ?: return null
    val schemaVersion = runCatching { document["schemaVersion"]?.jsonPrimitive }.getOrNull() ?: return null
    val version = runCatching { document["version"]?.jsonPrimitive }.getOrNull() ?: return null
    val revision = runCatching { document["releaseRevision"]?.jsonPrimitive }.getOrNull() ?: return null
    if (!type.isString || type.content != "KAST_CLI_BUILD_IDENTITY") return null
    if (schemaVersion.isString || schemaVersion.intOrNull != 1) return null
    if (!version.isString || !revision.isString) return null
    return runCatching {
        CliBuildIdentity(
            version = CliImplementationVersion(version.content),
            revision = ReleaseRevision(revision.content),
        )
    }.getOrNull()
}

private fun kastPluginVersion(): PluginVersion? =
    KastPluginBackend::class.java
        .getResource("/kast-backend-version.txt")
        ?.readText()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeIf { version -> version != "unknown" }
        ?.let(::PluginVersion)

private fun kastPluginRevision(): ReleaseRevision? =
    KastPluginBackend::class.java
        .getResource("/kast-backend-revision.txt")
        ?.readText()
        ?.trim()
        ?.let { revision -> runCatching { ReleaseRevision(revision) }.getOrNull() }
