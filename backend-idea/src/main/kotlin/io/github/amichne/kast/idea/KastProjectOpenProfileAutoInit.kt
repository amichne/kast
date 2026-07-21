package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastPluginBackend

import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.ProjectOpenProfileKind
import io.github.amichne.kast.api.contract.compatibility.CliImplementationVersion
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
        loadCliVersion: (Path) -> CliImplementationVersion? = ::loadConfiguredCliVersion,
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
                    val version = loadCliVersion(binary)
                    if (version == null) {
                        CliAuthorityLoadResult.Rejected(
                            "Configured Kast CLI at $binary did not report a valid implementation version; update the CLI and reopen this exact project.",
                        )
                    } else {
                        CliAuthorityLoadResult.Loaded(binary = binary, version = version)
                    }
                }
            },
            prepareWorkspace = prepareWorkspace,
        )

    internal fun executeWithDependencies(
        workspaceRoot: Path,
        config: KastConfig,
        loadInstallReceipt: () -> KastInstallReceiptLoadResult = KastInstallReceiptLoader::load,
        prepareWorkspace: (PluginWorkspaceBootstrapRequest) -> PluginWorkspaceBootstrapResult =
            PluginWorkspaceBootstrap::prepare,
    ): ProjectOpenProfileAutoInitResult =
        executeWithCliAuthorityResolver(
            workspaceRoot = workspaceRoot,
            config = config,
            resolveCliAuthority = {
                when (val result = loadInstallReceipt()) {
                    is KastInstallReceiptLoadResult.Loaded ->
                        CliAuthorityLoadResult.Loaded(
                            binary = result.binary,
                            version = result.version,
                        )
                    is KastInstallReceiptLoadResult.Rejected ->
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
        val cliAuthority = when (val result = resolveCliAuthority(pluginVersion)) {
            is CliAuthorityLoadResult.Loaded -> result
            is CliAuthorityLoadResult.Rejected ->
                return ProjectOpenProfileAutoInitResult.Failed(result.message)
        }
        val request = PluginWorkspaceBootstrapRequest(
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
            cliBinary = cliAuthority.binary,
            cliVersion = cliAuthority.version,
            pluginVersion = pluginVersion,
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

internal fun loadConfiguredCliVersion(binary: Path): CliImplementationVersion? {
    val process = runCatching {
        ProcessBuilder(binary.toString(), "version")
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return null
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }.trim()
    val version = output.removePrefix("Kast CLI ").takeIf { value ->
        output.startsWith("Kast CLI ") && value.isNotBlank() && value.none(Char::isWhitespace)
    } ?: return null
    return runCatching { CliImplementationVersion(version) }.getOrNull()
}

private fun kastPluginVersion(): PluginVersion? =
    KastPluginBackend::class.java
        .getResource("/kast-backend-version.txt")
        ?.readText()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeIf { version -> version != "unknown" }
        ?.let(::PluginVersion)
